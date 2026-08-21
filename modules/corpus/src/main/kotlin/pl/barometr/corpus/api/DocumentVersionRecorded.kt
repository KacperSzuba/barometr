package pl.barometr.corpus.api

import pl.barometr.ingestion.api.ExternalId
import pl.barometr.shared.ContentHash
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.SourceId
import java.time.Instant

/**
 * Published once per genuinely new version of a document — the second link in the
 * chain that starts at [pl.barometr.ingestion.api.RawDocumentIngested].
 *
 * Where the ingestion event says "these bytes are new", this one says "they are
 * version 3 of this document", which is the first statement anything downstream can
 * act on. Identity resolution, text extraction and indexing all hang off it without
 * corpus knowing any of them exist.
 *
 * It carries [connectorId] as well as [sourceId] because a consumer's question is
 * almost always about the source's *kind* — "is this an act from ISAP" — and looking
 * that up would mean every listener depending on the registry.
 */
data class DocumentVersionRecorded(
    val documentId: DocumentId,
    val versionId: DocumentVersionId,
    val sourceId: SourceId,
    val connectorId: ConnectorId,
    val externalId: ExternalId,
    val kind: DocumentKind,
    val contentHash: ContentHash,
    val versionNo: Int,
    val occurredAt: Instant,
)
