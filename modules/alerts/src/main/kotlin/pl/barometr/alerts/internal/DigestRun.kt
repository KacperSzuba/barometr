package pl.barometr.alerts.internal

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Asks, for everybody with something waiting, whether their window has closed.
 *
 * The waiting notifications *are* the buffer, rather than a second queue beside them:
 * nothing about one says which mode was in force when it was raised, so somebody
 * switching from immediate to daily keeps everything already matched and simply waits
 * for the new window. That is what the specification asks to be able to do, and a queue
 * per mode is how it would have been lost.
 *
 * Runs often and closes rarely. Asking "has a boundary passed" every few minutes is one
 * cheap query per person with something waiting; firing *at* each boundary instead would
 * need a scheduler that knows every user's local hour, and one missed tick would skip
 * somebody's day.
 *
 * Locked, because a window must close once across the deployment.
 */
@Component
class DigestRun(
    private val notifications: NotificationRepository,
    private val closer: DigestCloser,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.alerts.digest-interval:PT5M}", initialDelay = 90_000)
    @SchedulerLock(name = "alerts-digest")
    fun closeDueWindows() {
        val closed = notifications.ownersWaiting().count(closer::closeWindowFor)

        if (closed > 0) log.info("Closed {} digests", closed)
    }
}
