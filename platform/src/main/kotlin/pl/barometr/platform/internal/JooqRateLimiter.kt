package pl.barometr.platform.internal

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.barometr.platform.RateLimit
import pl.barometr.platform.RateLimiter
import pl.barometr.platform.internal.jooq.tables.references.RATE_LIMIT_BUCKET
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

/**
 * The token bucket, as one statement.
 *
 * **Refill is arithmetic done inside the upsert.** How many tokens have accrued since the
 * row was last touched is `elapsed / window * limit`, worked out in SQL so that two
 * requests arriving together cannot both read "one left" and both spend it. Nothing runs
 * on a schedule and an idle bucket costs nothing.
 *
 * **Its own transaction.** A limiter that joined the request's transaction would give the
 * tokens back when the request failed — which is precisely when a caller is hammering
 * something — and would hold a row lock for the length of whatever the request went on to
 * do.
 */
@Repository
class JooqRateLimiter(
    private val dsl: DSLContext,
    private val clock: Clock,
) : RateLimiter {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun consume(bucket: String, limit: Int, window: Duration): RateLimit {
        val now = clock.instant()
        val seconds = window.seconds.coerceAtLeast(1)
        val at = now.atOffset(ZoneOffset.UTC)

        // What the caller has now: what was left, plus whatever has accrued since the row
        // was last touched, capped at the bucket's size. Worked out inside the statement,
        // so two requests arriving together cannot both read "one left" and both spend it.
        val available = DSL.field(
            "least({0}, {1} + floor(extract(epoch from ({2}::timestamptz - {3})) * {0} / {4})::int)",
            Int::class.java,
            DSL.value(limit),
            RATE_LIMIT_BUCKET.TOKENS,
            DSL.value(at),
            RATE_LIMIT_BUCKET.REFILLED_AT,
            DSL.value(seconds),
        )

        // The `WHERE` is what makes the refusal unambiguous: with nothing available the
        // update matches nothing, returns nothing, and the request is refused — where a
        // `GREATEST(…, 0)` would leave a zero indistinguishable from having just spent the
        // last token.
        val left = dsl.insertInto(RATE_LIMIT_BUCKET)
            .set(RATE_LIMIT_BUCKET.BUCKET_KEY, bucket)
            .set(RATE_LIMIT_BUCKET.TOKENS, limit - 1)
            .set(RATE_LIMIT_BUCKET.REFILLED_AT, at)
            .onConflict(RATE_LIMIT_BUCKET.BUCKET_KEY)
            .doUpdate()
            .set(RATE_LIMIT_BUCKET.TOKENS, available.minus(DSL.inline(1)))
            .set(RATE_LIMIT_BUCKET.REFILLED_AT, at)
            .where(available.ge(DSL.inline(1)))
            .returningResult(RATE_LIMIT_BUCKET.TOKENS)
            .fetchOne()
            ?.value1()

        return RateLimit(
            allowed = left != null,
            limit = limit,
            remaining = left ?: 0,
            // When a full bucket would be back. A client that has been turned away needs
            // a time to come back at more than it needs an exact one.
            resetAt = now.plusSeconds(seconds),
        )
    }

    @Transactional
    override fun forgetIdleBuckets(idleFor: Duration): Int =
        dsl.deleteFrom(RATE_LIMIT_BUCKET)
            .where(RATE_LIMIT_BUCKET.REFILLED_AT.lt(clock.instant().minus(idleFor).atOffset(ZoneOffset.UTC)))
            .execute()
}
