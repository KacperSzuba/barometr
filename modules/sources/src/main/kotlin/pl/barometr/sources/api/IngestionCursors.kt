package pl.barometr.sources.api

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
