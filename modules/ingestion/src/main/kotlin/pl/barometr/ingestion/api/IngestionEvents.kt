package pl.barometr.ingestion.api

import pl.barometr.shared.ContentHash
import pl.barometr.sources.api.SourceId
import java.time.Instant
import java.util.UUID

/**
 * Published once per genuinely new payload — the first link in the processing
 * chain.
 *
 * Consumers annotate a handler with `@ApplicationModuleListener`, so Spring
 * Modulith records the publication and retries delivery. Text extraction,
 * identity resolution, indexing and profile matching all hang off this event
 * without ingestion knowing any of them exist.
 *
 * Re-ingesting known content publishes nothing, which is what stops a connector
 * replay from re-running the whole pipeline.
 */
data class RawDocumentIngested(
    val rawDocumentId: UUID,
    val sourceId: SourceId,
    val externalId: ExternalId,
    val contentHash: ContentHash,
    val kind: PayloadKind,
    val occurredAt: Instant,
)
