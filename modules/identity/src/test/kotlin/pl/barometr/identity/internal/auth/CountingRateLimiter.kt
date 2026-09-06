package pl.barometr.identity.internal.auth

import pl.barometr.platform.RateLimit
import pl.barometr.platform.RateLimiter
import java.time.Duration
import java.time.Instant

/**
 * A limiter that counts and never refills.
 *
 * Refilling is time's job and time is tested where the real limiter lives —
 * `JooqRateLimiterTest` advances a clock against the arithmetic that does it. What the
 * throttle needs from a limiter is which bucket was spent and whether it had anything
 * left, and a map answers that without a database.
 */
class CountingRateLimiter : RateLimiter {

    private val spent = mutableMapOf<String, Int>()

    override fun consume(bucket: String, limit: Int, window: Duration): RateLimit {
        val used = spent.merge(bucket, 1, Int::plus)!!

        return RateLimit(
            allowed = used <= limit,
            limit = limit,
            remaining = (limit - used).coerceAtLeast(0),
            resetAt = Instant.EPOCH.plus(window),
        )
    }

    override fun forgetIdleBuckets(idleFor: Duration): Int = spent.size.also { spent.clear() }

    /** How many times a bucket has been spent — which is what the throttle's choice of key decides. */
    fun spentOn(bucket: String): Int = spent[bucket] ?: 0

    val buckets: Set<String> get() = spent.keys
}
