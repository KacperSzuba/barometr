package pl.barometr.corpus.internal.diff

import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Goes back to the archive for the pairs no arriving text will ever announce.
 *
 * [ArrivingTextQueuesComparison] queues a comparison at the moment a version's text is
 * derived, and that moment happens once. Every version already in the archive when this
 * feature was written passed it long ago — and none of them will pass it again,
 * because the archive is content-addressed and a document nobody edits produces no
 * second version. Without this, the diff would only ever exist for documents revised
 * after the day it shipped.
 *
 * It is also what makes bumping [VersionComparison.READER_VERSION] a deployment rather
 * than a migration: every pair becomes uncompared under the new reading, and this walks
 * the archive re-queueing them at background priority.
 *
 * Bounded to a batch per run and resumed from where it stopped, so it never claims the
 * queue for itself.
 */
@Component
class VersionDiffBacklogSweep(
    private val diffs: VersionDiffRepository,
    private val queue: VersionDiffQueue,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Every quarter of an hour, and three minutes after the application starts: often
     * enough to drain a backlog within a day, rare enough that a system with nothing to
     * do spends four indexed queries an hour finding that out. The interval is a placeholder rather than a
     * field of [DiffProperties] because an annotation takes a constant.
     */
    @Scheduled(fixedDelayString = "\${app.corpus.diff.sweep-interval:PT15M}", initialDelay = 180_000)
    @SchedulerLock(name = "corpus-version-diff-sweep")
    fun queueComparisonsTheArchiveIsMissing() {
        val waiting = diffs.pairsAwaitingComparison(VersionComparison.READER_VERSION, after = null, limit = BATCH)
        if (waiting.isEmpty()) return

        val queued = waiting.count(queue::queueComparison)
        meters.counter("corpus.diff.queued", "by", "sweep").increment(queued.toDouble())
        log.info("Queued {} of {} uncompared version pairs", queued, waiting.size)
    }

    private companion object {
        /**
         * How many pairs one run queues. Two hundred is a few minutes of background
         * work, so a sweep that finds a five-year backlog spreads it over hours instead
         * of filling the queue with it in one pass.
         */
        const val BATCH = 200
    }
}
