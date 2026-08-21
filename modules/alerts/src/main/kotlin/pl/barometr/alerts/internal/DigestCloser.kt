package pl.barometr.alerts.internal

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import java.time.Clock

/**
 * Closes one person's window, if one is due.
 *
 * Its own class rather than a method on the run, because the transaction is the point:
 * opening a digest and putting the notifications in it must happen together. A crash
 * between them would leave an empty digest that nobody can see and a "last window"
 * marker that has moved — which is how somebody's daily digest silently skips a day.
 * A private method called from the same object would have been outside the proxy and
 * outside the transaction.
 */
@Service
class DigestCloser(
    private val notifications: NotificationRepository,
    private val digests: DigestRepository,
    private val preferences: DeliveryPreferences,
    private val schedule: DigestSchedule,
    private val clock: Clock,
) {

    /** False when nothing was due, which is the ordinary case. */
    @Transactional
    fun closeWindowFor(owner: UserId): Boolean {
        val waiting = notifications.waitingFor(owner)
        if (waiting.isEmpty()) return false

        val preference = preferences.forOwner(owner)
        val going = waiting.filter { goesNow(it, preference, waiting) }

        // An empty digest is not sent. Somebody whose week was quiet hears nothing —
        // a mail saying "nothing happened" is the one nobody opens the next time.
        if (going.isEmpty()) return false

        val digest = digests.open(owner)
        notifications.attachTo(digest, going)
        return true
    }

    /**
     * Critical goes out of any window and through the quiet hours; that is the whole
     * meaning of somebody marking a profile that way. Everything else waits for a
     * boundary, and then for the morning.
     */
    private fun goesNow(
        notification: Notification,
        preference: DeliveryPreference,
        waiting: List<Notification>,
    ): Boolean =
        notification.urgency == Urgency.CRITICAL ||
            (windowClosed(preference, waiting) && !schedule.quiet(preference, clock.instant()))

    /**
     * The boundary is measured from the last digest, or — for somebody who has never had
     * one — from the oldest thing waiting. Otherwise a daily digest at eight would send
     * at nine this morning on the grounds that eight had passed.
     */
    private fun windowClosed(preference: DeliveryPreference, waiting: List<Notification>): Boolean {
        val since = digests.lastFor(preference.owner) ?: waiting.first().createdAt

        return schedule.windowClosed(preference, clock.instant(), since)
    }
}
