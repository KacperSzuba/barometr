package pl.barometr.ingestion.internal

import pl.barometr.ingestion.api.ExternalId
import pl.barometr.ingestion.api.PayloadKind
import pl.barometr.shared.ContentHash
import pl.barometr.sources.api.SourceId
import java.util.UUID

/** What ingestion knows about a document before it has been recorded. */
data class NewRawDocument(
    val sourceId: SourceId,
    val externalId: ExternalId,
    val contentHash: ContentHash,
    val blobKey: String,
    val payloadKind: PayloadKind,
    val etag: String?,
    val lastModified: String?,
    val runId: UUID?,
)
