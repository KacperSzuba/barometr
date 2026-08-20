package pl.barometr.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * A clock a test can move.
 *
 * The alternative is what the queue's backoff test used to do: issue an `UPDATE`
 * to drag `run_after` into the past, which tests the database's willingness to
 * accept the update rather than the policy under test. Anything that decides
 * something from the time takes a [Clock]; here, tests decide what the time is.
 */
class TestClock(
    private var instant: Instant = Instant.parse("2026-08-20T10:00:00Z"),
    private val zone: ZoneId = ZoneId.of("UTC"),
) : Clock() {

    override fun instant(): Instant = instant

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = TestClock(instant, zone)

    fun advanceBy(amount: Duration) {
        instant = instant.plus(amount)
    }
}
