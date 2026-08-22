package pl.barometr.corpus.api

import pl.barometr.shared.ContentHash
import java.time.Instant

/**
 * Published once a version's plain text exists — the third link in the chain that
 * starts at [pl.barometr.ingestion.api.RawDocumentIngested].
 *
 * Where [DocumentVersionRecorded] says "these bytes are version three of this
 * document", this says "and here is what it says, in characters anything can count".
 * It is the first event a consumer can act on without knowing what a PDF is.
 *
 * [textHash] addresses the text in the derived bucket, and every offset any claim
 * ever cites — a summary sentence, a diff, an embedded chunk — is an index into
 * *that* text and never into the original file. Carrying the hash rather than the
 * text keeps the event small and makes the reference verifiable: a consumer that
 * fetches it gets exactly the characters the offsets were measured against.
 */
data class DocumentTextExtracted(
    val documentId: DocumentId,
    val versionId: DocumentVersionId,
    val textHash: ContentHash,
    val textLength: Int,
    val chunkCount: Int,
    val occurredAt: Instant,
)
