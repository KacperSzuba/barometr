package pl.barometr.ingestion.api

import pl.barometr.sources.api.ConnectorId
// `IngestionMode` belongs to the source registry, which decides how a source is
// read; the SPI only needs to name the mode it was invoked in.
import pl.barometr.sources.api.IngestionMode
import java.time.LocalDate

/**
 * Where a connector resumes from.
 *
 * Untyped on purpose: one source paginates by date, another by print number, a
 * third by opaque continuation token. Typing this would mean a schema migration
 * every time one connector learns a new trick.
 */
data class Cursor(val mode: IngestionMode, val position: Map<String, String>) {
    operator fun get(key: String): String? = position[key]

    companion object {
        fun start(mode: IngestionMode) = Cursor(mode, emptyMap())

        /**
         * Written into a partition's position by a backfill connector once it has
         * read that partition to the end, and read by the dispatcher to decide
         * whether to bring the partition back.
         *
         * The one key in an otherwise opaque map that is a contract between the two,
         * so it is declared where the contract is. It used to be spelled out
         * separately in the dispatcher and in each connector — three declarations of
         * one string, any of which could have drifted.
         */
        const val PARTITION_DONE = "done"
    }
}

/**
 * What a connector reports about a pass, beyond what the sink already knows.
 *
 * Deliberately carries no document counts: the sink is handed every payload and is
 * the only thing that can tell a new one from content the archive already held, so
 * a connector counting alongside it produced a second set of numbers that could
 * disagree — and did, since the runner read one set on success and the other on
 * failure.
 */
data class FetchResult(
    /** Null when there is nothing more to resume from. */
    val nextCursor: Cursor?,
    /** Backfill only: this partition has been read to the end. */
    val exhausted: Boolean = false,
    /**
     * The source itself said nothing changed, so no collection was fetched.
     *
     * Distinct from "fetched and found nothing", and the distinction matters: a
     * healthy quarter-hour poll of an idle source legitimately stores zero
     * documents, and without this flag the volume-anomaly check would report an
     * outage every fifteen minutes.
     */
    val sourceUnchanged: Boolean = false,
) {
    companion object {
        val NOTHING = FetchResult(nextCursor = null)
    }
}

/**
 * A connector reads from one source and hands payloads to a sink. That is all
 * it does.
 *
 * What it supports is expressed by the interfaces it implements, not by a list it
 * declares: a connector that reads increments is an [IncrementalConnector], and a
 * runtime asking for a mode it does not implement is a compile-time-visible fact
 * rather than a set that can disagree with the type.
 *
 * Methods block rather than suspend. The application runs on virtual threads, so
 * blocking IO costs nothing worth the complexity of a second concurrency model —
 * and a connector implementation stays readable top to bottom, which matters when
 * the interesting part is a source's pagination quirks.
 */
interface Connector {
    /** Identifies the connector to the registry, to configuration and to logs. */
    val id: ConnectorId
}

interface IncrementalConnector : Connector {
    /**
     * Reads what changed since [cursor]. Must be safe to call with a cursor it
     * has already processed: re-reading a range is a no-op at the sink, so
     * correctness never depends on the cursor being exact.
     */
    fun fetch(cursor: Cursor?, sink: RawDocumentSink): FetchResult
}

/** A resumable unit of archive: one parliamentary term, one year, one month. */
data class BackfillPartition(
    val key: String,
    val label: String,
)

interface BackfillConnector : Connector {
    /**
     * Splits a window into units that can be interrupted and resumed
     * independently — the difference between a backfill that survives a restart
     * and one that starts over.
     */
    fun partitions(from: LocalDate, to: LocalDate): List<BackfillPartition>

    fun fetchPartition(
        partition: BackfillPartition,
        cursor: Cursor?,
        sink: RawDocumentSink,
    ): FetchResult
}
