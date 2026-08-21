package pl.barometr.alerts.internal

import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZonedDateTime

/**
 * When a window closes, and when the house is asleep.
 *
 * A pure decision, deliberately holding nothing: given a preference, the moment, and
 * when this person last heard from us, it says whether a boundary has passed. Every
 * awkward case in a schedule is a calendar question — the hour that does not exist on
 * the night the clocks change, the week that starts on Monday — and answering those
 * against a database would mean starting containers to find out what Tuesday is.
 */
@Component
class DigestSchedule {

    /**
     * Whether a boundary has passed since [since].
     *
     * [since] is when this person last had a digest, or — if they never have — when the
     * oldest thing now waiting was raised. That second reading is what makes a daily
     * digest at eight behave: something matched at nine this morning waits for tomorrow
     * rather than going out immediately because "eight has passed".
     */
    fun windowClosed(preference: DeliveryPreference, now: Instant, since: Instant): Boolean {
        val local = now.atZone(preference.zone)

        return when (preference.mode) {
            // No boundary to wait for: whatever is waiting has been waiting long enough.
            DeliveryMode.IMMEDIATE -> true
            DeliveryMode.HOURLY -> local.truncatedToHour().passedSince(since)
            DeliveryMode.DAILY -> local.mostRecentAt(preference.atHour!!).passedSince(since)
            DeliveryMode.WEEKLY -> local
                .mostRecentOn(preference.onWeekday!!, preference.atHour!!)
                .passedSince(since)
        }
    }

    /** Whether ordinary notifications should wait for the morning. */
    fun quiet(preference: DeliveryPreference, now: Instant): Boolean =
        preference.quiet?.covers(now.atZone(preference.zone).hour) ?: false

    private fun ZonedDateTime.truncatedToHour() = withMinute(0).withSecond(0).withNano(0)

    /** Today's boundary if it has passed, yesterday's otherwise. */
    private fun ZonedDateTime.mostRecentAt(hour: Int): ZonedDateTime =
        truncatedToHour().withHour(hour).let { if (it > this) it.minusDays(1) else it }

    /**
     * The last time this weekday and hour came round, which is today only if today is
     * that day and the hour has passed.
     */
    private fun ZonedDateTime.mostRecentOn(weekday: Int, hour: Int): ZonedDateTime {
        val onDay = truncatedToHour().withHour(hour).plusDays(daysBack(weekday).toLong())
        return if (onDay > this) onDay.minusWeeks(1) else onDay
    }

    private fun ZonedDateTime.daysBack(weekday: Int) = weekday - dayOfWeek.value

    private fun ZonedDateTime.passedSince(since: Instant) = toInstant() > since
}
