package pl.barometr.ingestion.internal

import pl.barometr.sources.api.IngestionMode
import pl.barometr.sources.api.RunId
import pl.barometr.sources.api.RunOutcome
import pl.barometr.sources.api.RunReport
import pl.barometr.sources.api.SourceId
import pl.barometr.sources.api.SourceRuns
import pl.barometr.shared.Ids
import java.time.Instant

/** The run history, in a list — what `sources` would write down, without its schema. */
class RecordingRuns : SourceRuns {

    data class Finished(val runId: RunId, val outcome: RunOutcome, val report: RunReport)

    val started = mutableListOf<RunId>()
    val finished = mutableListOf<Finished>()

    /** What [recentAverageDocumentsSeen] should answer; null means "no history yet". */
    var recentAverage: Double? = null

    override fun start(sourceId: SourceId, mode: IngestionMode): RunId =
        RunId(Ids.next()).also { started += it }

    override fun finish(runId: RunId, outcome: RunOutcome, report: RunReport) {
        finished += Finished(runId, outcome, report)
    }

    override fun lastFinishedAt(sourceId: SourceId, mode: IngestionMode): Instant? = null

    override fun recentAverageDocumentsSeen(sourceId: SourceId, mode: IngestionMode, runs: Int): Double? =
        recentAverage

    val onlyFinished: Finished get() = finished.single()
}
