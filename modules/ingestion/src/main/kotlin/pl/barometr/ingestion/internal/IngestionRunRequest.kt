package pl.barometr.ingestion.internal

import pl.barometr.sources.api.IngestionMode
import pl.barometr.sources.api.SourceId

/**
 * A queued run as the handler receives it.
 *
 * Typed, so the strings that travelled through the queue's `jsonb` payload are
 * converted once at the boundary — rather than being compared as text three classes
 * later, which is what a stringified UUID in a `firstOrNull` amounts to.
 */
data class IngestionRunRequest(
    val sourceId: SourceId,
    val mode: IngestionMode,
    /** Empty for incremental, which has a single position to resume from. */
    val partition: String,
)
