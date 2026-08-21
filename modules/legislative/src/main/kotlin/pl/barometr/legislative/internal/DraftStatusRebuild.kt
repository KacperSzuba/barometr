package pl.barometr.legislative.internal

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Rebuilds the read model from the history it is derived from.
 *
 * On a schedule rather than on every event, because the medians every estimate rests
 * on are measured across the whole archive: recomputing them per document would put an
 * aggregate over the entire history behind each of ten thousand ingested processes.
 * Hourly is far inside the resolution of what is being estimated — stages take weeks —
 * and the one place freshness genuinely matters, a single draft's card, is computed
 * live and never reads this table.
 *
 * Locked, because it must happen once across the deployment rather than once per
 * instance.
 */
@Component
class DraftStatusRebuild(
    private val drafts: DraftRepository,
    private val transitions: StageTransitionRepository,
    private val paces: StagePaceRepository,
    private val statuses: DraftStatusRepository,
    private val engine: DraftStatusEngine,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.legislative.status-rebuild-interval:PT1H}", initialDelay = 60_000)
    @SchedulerLock(name = "legislative-status-rebuild")
    fun rebuildDraftStatuses() {
        val measured = paces.measure()
        val summaries = drafts.allSummaries()

        val recorded = summaries.count { draft ->
            val status = engine.statusOf(draft, transitions.historyOf(draft.id), measured)
            status?.also { statuses.record(draft.id, it) } != null
        }

        log.info("Rebuilt status for {} of {} drafts", recorded, summaries.size)
    }
}
