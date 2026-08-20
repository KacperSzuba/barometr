package pl.barometr.sources.api

interface SourceRuns {
    fun start(sourceId: SourceId, mode: IngestionMode): RunId

    fun finish(runId: RunId, outcome: RunOutcome, report: RunReport)

    /**
     * When this source was last read to completion, successfully or not. Null when
     * it has never run.
     *
     * This is what drives the cadence. Deriving "is a run due" from observed state
     * rather than from a chain of self-scheduling jobs matters: a chain has to
     * enqueue its own successor while it is still running, which the queue's dedup
     * key correctly refuses — so the chain would quietly stop after one run.
     */
    fun lastFinishedAt(sourceId: SourceId, mode: IngestionMode): java.time.Instant?

    /**
     * Mean documents seen across recent successful runs, or null when there is no
     * history yet. Feeds the volume-anomaly check.
     */
    fun recentAverageDocumentsSeen(sourceId: SourceId, mode: IngestionMode, runs: Int): Double?
}
