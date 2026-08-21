package pl.barometr.alerts.internal

import pl.barometr.identity.api.UserId
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a window closes — the calendar questions, asked without a database.
 *
 * Every awkward case here is a calendar case: the hour a daily digest means in Warsaw
 * rather than UTC, the night the clocks go forward, quiet hours that wrap midnight.
 * None of them needs a container to answer, and answering them in one would have made
 * the awkward ones too expensive to write down.
 */
class DigestScheduleTest {

    private val schedule = DigestSchedule()
    private val owner = UserId.next()

    @Test
    fun `immediate has no boundary to wait for`() {
        val preference = DeliveryPreference(owner, DeliveryMode.IMMEDIATE)

        assertTrue(schedule.windowClosed(preference, at("2026-08-22T09:00:00Z"), at("2026-08-22T08:59:00Z")))
    }

    @Test
    fun `hourly closes when the hour turns, not sixty minutes after the last one`() {
        val preference = DeliveryPreference(owner, DeliveryMode.HOURLY)

        // Last digest at 09:50, now 10:05: the ten o'clock boundary is between them.
        assertTrue(schedule.windowClosed(preference, at("2026-08-22T10:05:00Z"), at("2026-08-22T09:50:00Z")))
        // Both inside the same hour: nothing has turned.
        assertFalse(schedule.windowClosed(preference, at("2026-08-22T10:40:00Z"), at("2026-08-22T10:05:00Z")))
    }

    /**
     * The hour is theirs. A daily digest "at 8" in Warsaw closes at 06:00 UTC in
     * summer, and a server reading its own clock would send it two hours late.
     */
    @Test
    fun `a daily window closes at the local hour, not the server's`() {
        val preference = DeliveryPreference(owner, DeliveryMode.DAILY, atHour = 8)

        assertFalse(schedule.windowClosed(preference, at("2026-08-22T05:30:00Z"), at("2026-08-21T20:00:00Z")))
        assertTrue(schedule.windowClosed(preference, at("2026-08-22T06:30:00Z"), at("2026-08-21T20:00:00Z")))
    }

    /**
     * The case that makes the reference point matter: somebody's first-ever match
     * arrives after today's boundary, so it waits for tomorrow's rather than going out
     * on the grounds that eight o'clock has been and gone.
     */
    @Test
    fun `something raised after today's boundary waits for tomorrow's`() {
        val preference = DeliveryPreference(owner, DeliveryMode.DAILY, atHour = 8)
        val raisedAtNine = at("2026-08-22T07:00:00Z")

        assertFalse(schedule.windowClosed(preference, at("2026-08-22T12:00:00Z"), raisedAtNine))
        assertTrue(schedule.windowClosed(preference, at("2026-08-23T06:30:00Z"), raisedAtNine))
    }

    @Test
    fun `a weekly window closes on its own day`() {
        // Thursday at 9, local. 2026-08-20 is a Thursday.
        val preference = DeliveryPreference(owner, DeliveryMode.WEEKLY, atHour = 9, onWeekday = 4)
        val lastWeek = at("2026-08-13T08:00:00Z")

        assertFalse(schedule.windowClosed(preference, at("2026-08-19T20:00:00Z"), lastWeek))
        assertTrue(schedule.windowClosed(preference, at("2026-08-20T07:30:00Z"), lastWeek))
    }

    @Test
    fun `a weekly window closes once, not every day after its day`() {
        val preference = DeliveryPreference(owner, DeliveryMode.WEEKLY, atHour = 9, onWeekday = 4)
        val thisThursday = at("2026-08-20T07:30:00Z")

        assertFalse(schedule.windowClosed(preference, at("2026-08-21T07:30:00Z"), thisThursday))
        assertFalse(schedule.windowClosed(preference, at("2026-08-25T07:30:00Z"), thisThursday))
        assertTrue(schedule.windowClosed(preference, at("2026-08-27T07:30:00Z"), thisThursday))
    }

    /**
     * The night the clocks go forward, 02:00 local does not exist. `java.time` moves an
     * impossible local time forward by the gap rather than throwing, so a digest set for
     * that hour closes at 03:00 instead of never.
     */
    @Test
    fun `a boundary in the hour the clocks skip still closes`() {
        val preference = DeliveryPreference(owner, DeliveryMode.DAILY, atHour = 2)

        // Warsaw skips 02:00 on 2027-03-28; 01:00 UTC is 03:00 local.
        assertTrue(schedule.windowClosed(preference, at("2027-03-28T02:00:00Z"), at("2027-03-27T12:00:00Z")))
    }

    @Test
    fun `a zone the person chose is the zone the boundary is read in`() {
        val warsaw = DeliveryPreference(owner, DeliveryMode.DAILY, atHour = 8)
        val newYork = warsaw.copy(zone = ZoneId.of("America/New_York"))
        val yesterday = at("2026-08-21T18:00:00Z")
        val now = at("2026-08-22T06:30:00Z")

        assertTrue(schedule.windowClosed(warsaw, now, yesterday))
        // The same moment is half past two in the morning in New York.
        assertFalse(schedule.windowClosed(newYork, now, yesterday))
    }

    @Test
    fun `quiet hours wrap midnight, which is the way people set them`() {
        val preference = DeliveryPreference(owner, DeliveryMode.IMMEDIATE, quiet = QuietHours(22, 7))

        assertTrue(schedule.quiet(preference, at("2026-08-22T21:30:00Z"))) // 23:30 local
        assertTrue(schedule.quiet(preference, at("2026-08-22T03:00:00Z"))) // 05:00 local
        assertFalse(schedule.quiet(preference, at("2026-08-22T09:00:00Z"))) // 11:00 local
    }

    @Test
    fun `no quiet hours means no quiet hours`() {
        val preference = DeliveryPreference(owner, DeliveryMode.IMMEDIATE)

        assertFalse(schedule.quiet(preference, at("2026-08-22T02:00:00Z")))
    }

    private fun at(moment: String) = Instant.parse(moment)
}
