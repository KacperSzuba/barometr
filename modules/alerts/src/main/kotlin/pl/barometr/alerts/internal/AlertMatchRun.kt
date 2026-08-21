package pl.barometr.alerts.internal

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Drains the buffer: everything that moved since the last run, judged in one pass.
 *
 * A batch rather than a listener, and the reason is arithmetic. One crawl of the
 * Journal of Laws restates thousands of acts; asking every profile about each of them
 * as it arrives turns a single indexed query into thousands. Judging them together
 * also lets the twenty-four-hour window mean what it says — a draft that moved three
 * times while the buffer waited produces one notification, not three.
 *
 * Locked, because it must happen once across the deployment. Two instances judging the
 * same item would each stop at the unique index rather than send twice, but they would
 * both do all the work to find that out.
 */
@Component
class AlertMatchRun(
    private val pending: PendingItemRepository,
    private val items: BufferedItemReader,
    private val raiser: AlertRaiser,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.alerts.match-interval:PT5M}", initialDelay = 45_000)
    @SchedulerLock(name = "alerts-match")
    fun raiseWaitingAlerts() {
        var judged = 0
        var raised = 0
        var passes = 0

        while (passes++ < MAX_BATCHES) {
            val batch = pending.waiting(BATCH)
            if (batch.isEmpty()) break

            batch.forEach { item ->
                // Something the archive no longer describes is still judged: leaving it
                // waiting would have every later run read it again, for ever.
                items.read(item)?.let { raised += raiser.raiseFor(it) }
                pending.markJudged(item.id)
                judged++
            }
        }

        if (judged > 0) log.info("Judged {} items, raised {} notifications", judged, raised)
    }

    private companion object {
        const val BATCH = 200

        /**
         * A bound rather than "until empty": a backfill can leave a hundred thousand
         * items waiting, and a run that worked through all of them would hold the lock
         * for an hour and delay everything published in the meantime. The next run
         * picks up where this one stopped.
         */
        const val MAX_BATCHES = 25
    }
}
