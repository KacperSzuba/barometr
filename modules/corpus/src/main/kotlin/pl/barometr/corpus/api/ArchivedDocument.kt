package pl.barometr.corpus.api

import pl.barometr.ingestion.api.ExternalId
import java.time.Instant

/**
 * What corpus will say about a document across the boundary.
 *
 * A value type, never a record: another context reading a row would be coupled to
 * this one's columns, and the whole point of deriving the corpus is that its shape
 * stays free to change.
 */
data class ArchivedDocument(
    val id: DocumentId,
    val externalId: ExternalId,
    val kind: DocumentKind,
    val title: String?,
    val publishedAt: Instant?,
)
