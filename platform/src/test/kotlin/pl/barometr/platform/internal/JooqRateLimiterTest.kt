package pl.barometr.platform.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.platform.internal.jooq.tables.references.RATE_LIMIT_BUCKET
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The token bucket, against the database that holds it.
 *
 * Every property here is one a per-process limiter does not have: it survives a restart,
 * it is the same bucket on every instance, and it refills by arithmetic rather than by
 * something running on a schedule.
 */
class JooqRateLimiterTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val limiter = JooqRateLimiter(dsl, clock)

    private val window = Duration.ofHours(1)

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(RATE_LIMIT_BUCKET).execute()
    }

    @Test
    fun `the first request of all is allowed and costs a token`() {
        val first = limiter.consume("ip:203.0.113.7", limit = 3, window = window)

        assertTrue(first.allowed)
        assertEquals(3, first.limit)
        assertEquals(2, first.remaining)
        assertEquals(clock.instant().plus(window), first.resetAt)
    }

    @Test
    fun `a bucket empties, and the request that empties it is still allowed`() {
        repeat(2) { assertTrue(limiter.consume(BUCKET, limit = 3, window = window).allowed) }

        val last = limiter.consume(BUCKET, limit = 3, window = window)

        assertTrue(last.allowed, "the token it spent existed")
        assertEquals(0, last.remaining)
    }

    @Test
    fun `an empty bucket refuses, and says when to come back`() {
        repeat(3) { limiter.consume(BUCKET, limit = 3, window = window) }

        val refused = limiter.consume(BUCKET, limit = 3, window = window)

        assertFalse(refused.allowed)
        assertEquals(0, refused.remaining)
        assertEquals(clock.instant().plus(window), refused.resetAt)
    }

    /** Nothing runs on a schedule: what has accrued is worked out when somebody asks. */
    @Test
    fun `waiting refills the bucket in proportion to the time waited`() {
        repeat(4) { limiter.consume(BUCKET, limit = 4, window = window) }
        assertFalse(limiter.consume(BUCKET, limit = 4, window = window).allowed)

        clock.advanceBy(Duration.ofMinutes(30))

        val half = limiter.consume(BUCKET, limit = 4, window = window)
        assertTrue(half.allowed)
        assertEquals(1, half.remaining, "half a window is half a bucket, minus the one just spent")
    }

    @Test
    fun `a caller who disappears for a month comes back to a full bucket`() {
        repeat(4) { limiter.consume(BUCKET, limit = 4, window = window) }

        clock.advanceBy(Duration.ofDays(30))

        assertEquals(3, limiter.consume(BUCKET, limit = 4, window = window).remaining)
    }

    @Test
    fun `two callers are two buckets`() {
        repeat(3) { limiter.consume("ip:203.0.113.7", limit = 3, window = window) }

        assertFalse(limiter.consume("ip:203.0.113.7", limit = 3, window = window).allowed)
        assertTrue(limiter.consume("ip:198.51.100.9", limit = 3, window = window).allowed)
    }

    /** A bucket at full is indistinguishable from one that never existed. */
    @Test
    fun `idle buckets are forgotten, and busy ones are left alone`() {
        limiter.consume("ip:203.0.113.7", limit = 3, window = window)
        clock.advanceBy(Duration.ofDays(2))
        limiter.consume("ip:198.51.100.9", limit = 3, window = window)

        assertEquals(1, limiter.forgetIdleBuckets(Duration.ofDays(1)))
        assertEquals(1, dsl.fetchCount(RATE_LIMIT_BUCKET))
    }

    private companion object {
        const val BUCKET = "key:01a07146-0000-0000-0000-000000000001"
    }
}
