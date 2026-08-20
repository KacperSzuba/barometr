package pl.barometr.sources.api

import pl.barometr.shared.Ids
import java.net.URI
import java.time.Duration
import java.util.UUID

enum class IngestionMode(val wireName: String) {
    /** Every few minutes, resuming from the last cursor. */
    INCREMENTAL("incremental"),

    /**
     * Years of archive, deliberately slow. A separate mode rather than a flag,
     * because it needs its own rate limit, its own cursor and a priority low
     * enough that a five-year replay never delays today's documents.
     */
    BACKFILL("backfill"),
    ;

    companion object {
        fun of(wireName: String): IngestionMode =
            entries.firstOrNull { it.wireName == wireName }
                ?: error("Unknown ingestion mode '$wireName'")
    }
}

data class SourceDefinition(
    val id: SourceId,
    val connectorId: ConnectorId,
    val name: String,
    val baseUrl: URI,
    val refreshInterval: Duration,
    /**
     * What a healthy run looks like. Without a baseline, a source answering
     * HTTP 200 with zero records is indistinguishable from a quiet day — and that
     * is the most common failure in this class of system.
     */
    val expectedMinRecordsPerRun: Int?,
)

/** Read port over the registry. Nothing outside `sources` touches its tables. */
interface SourceRegistry {
    fun enabled(): List<SourceDefinition>

    fun byConnector(connectorId: ConnectorId): SourceDefinition?

    /**
     * One enabled source by id, or null.
     *
     * Deliberately filtered to enabled sources rather than returning any row: the
     * caller is a queued job asking whether this source is still one we are allowed
     * to read, and a source switched off between enqueue and claim must not run.
     */
    fun enabledById(id: SourceId): SourceDefinition?
}

/**
 * Where each connector left off.
 *
 * Untyped positions on purpose: one source paginates by date, another by print
 * number, a third by opaque token. Typing this would mean a migration every time
 * one connector learns a new trick.
 */
interface IngestionCursors {
    /**
     * [partition] names an independently resumable unit of a backfill — a
     * parliamentary term, a year. Empty for incremental mode, which has only one
     * position to remember.
     */
    fun load(sourceId: SourceId, mode: IngestionMode, partition: String = ""): Map<String, String>?

    fun save(
        sourceId: SourceId,
        mode: IngestionMode,
        position: Map<String, String>,
        partition: String = "",
    )

    /**
     * Every partition position recorded for this mode, keyed by partition.
     *
     * The dispatcher uses it to find replays that still have work left: a backfill
     * runs in bounded chunks, so a partition is resumed until its own cursor says
     * it is finished.
     */
    fun partitions(sourceId: SourceId, mode: IngestionMode): Map<String, Map<String, String>>
}

@JvmInline
value class RunId(val value: UUID) {
    companion object {
        fun next() = RunId(Ids.next())
    }
}

enum class RunOutcome(val wireName: String) {
    SUCCEEDED("succeeded"),

    /** Some documents made it through, some did not. Worth knowing separately. */
    PARTIAL("partial"),
    FAILED("failed"),
}

data class RunReport(
    val documentsSeen: Int,
    val documentsStored: Int,
    val errors: Int,
    /** Fields the response carried unexpectedly, or omitted. Recorded, not thrown. */
    val schemaWarnings: List<String>,
    val failureReason: String? = null,
)

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
