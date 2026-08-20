package pl.barometr.ingestion.internal

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.barometr.ingestion.api.Cursor
import pl.barometr.sources.api.IngestionCursors
import pl.barometr.sources.api.IngestionMode
import pl.barometr.sources.api.SourceDefinition
import pl.barometr.sources.api.SourceRegistry
import pl.barometr.sources.api.SourceRuns
import java.time.Clock
import java.time.Instant

/**
 * Decides when a source is due and queues the work.
 *
 * Cadence is derived from observed state — the last finished run — rather than from
 * jobs that schedule their own successors. A self-chaining job has to enqueue its
 * replacement while it is still running, which the dedup key correctly refuses, so
 * the chain stops after one run. Reading `lastFinishedAt` instead has no such failure
 * mode: whatever happened to previous runs, the next one becomes due on time.
 *
 * Three mechanisms keep it from double-firing, each covering a different case:
 * `@SchedulerLock` stops two instances dispatching at once, the queue's dedup key
 * stops a second job while one is pending or running, and the interval check stops
 * a source being read more often than it wants.
 */
@Component
class IngestionScheduler(
    private val sources: SourceRegistry,
    private val runs: SourceRuns,
    private val cursors: IngestionCursors,
    private val runQueue: IngestionRunQueue,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.ingestion.dispatch-interval:60000}", initialDelay = 10_000)
    @SchedulerLock(name = "ingestion-dispatch")
    fun dispatchDueSources() {
        val now = clock.instant()

        sources.enabled().forEach { source ->
            dispatchIncremental(source, now)
            resumeUnfinishedBackfills(source, now)
        }
    }

    private fun dispatchIncremental(source: SourceDefinition, now: Instant) {
        val lastFinished = runs.lastFinishedAt(source.id, IngestionMode.INCREMENTAL)
        val due = lastFinished == null || !lastFinished.plus(source.refreshInterval).isAfter(now)
        if (!due) return

        if (runQueue.queueRun(source, IngestionMode.INCREMENTAL, now)) {
            log.debug("Queued incremental run for {}", source.connectorId)
        }
    }

    /**
     * Brings a partition back for its next chunk.
     *
     * A backfill reads in bounded chunks so that progress is durable, which means
     * something has to keep asking for the next one. The job itself cannot: it would
     * have to enqueue its successor while still running, and the dedup key correctly
     * refuses that. Driving it from the recorded cursor has no such problem — the
     * partition is resumed until its own position says it is finished.
     */
    private fun resumeUnfinishedBackfills(source: SourceDefinition, now: Instant) {
        cursors.partitions(source.id, IngestionMode.BACKFILL)
            .filterValues { position -> position[Cursor.PARTITION_DONE] != "true" }
            .keys
            .forEach { partition ->
                if (runQueue.queueRun(source, IngestionMode.BACKFILL, now, partition)) {
                    log.info("Resumed backfill partition {} of {}", partition, source.connectorId)
                }
            }
    }
}
