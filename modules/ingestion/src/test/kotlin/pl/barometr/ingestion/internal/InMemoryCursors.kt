package pl.barometr.ingestion.internal

import pl.barometr.sources.api.IngestionCursors
import pl.barometr.sources.api.IngestionMode
import pl.barometr.sources.api.SourceId

/**
 * Where each connector left off, in a map.
 *
 * Keyed exactly as the table is — source, mode and partition together — because the
 * thing most worth catching here is a runner that saves a backfill partition's
 * position over the incremental one.
 */
class InMemoryCursors : IngestionCursors {

    private data class Key(val sourceId: SourceId, val mode: IngestionMode, val partition: String)

    private val positions = mutableMapOf<Key, Map<String, String>>()

    override fun load(sourceId: SourceId, mode: IngestionMode, partition: String): Map<String, String>? =
        positions[Key(sourceId, mode, partition)]

    override fun save(
        sourceId: SourceId,
        mode: IngestionMode,
        position: Map<String, String>,
        partition: String,
    ) {
        positions[Key(sourceId, mode, partition)] = position
    }

    override fun partitions(sourceId: SourceId, mode: IngestionMode): Map<String, Map<String, String>> =
        positions.filterKeys { it.sourceId == sourceId && it.mode == mode && it.partition.isNotEmpty() }
            .mapKeys { (key, _) -> key.partition }
}
