package pl.barometr.corpus.api

import java.time.Instant

/**
 * Published once two versions of a document have been compared — the fourth link in
 * the chain that starts at [pl.barometr.ingestion.api.RawDocumentIngested].
 *
 * Thin, like the three before it: it says which comparison exists and nothing about
 * what it found. Whoever cares reads it through [DocumentDiffs], which keeps one
 * description of a change in one place.
 *
 * It exists now because the next question the product asks is asked of exactly this
 * event: which comment from the public consultation preceded which change. That
 * derivation needs to know a comparison happened; it has no business waiting on a
 * poll to find out.
 */
data class DocumentVersionsCompared(
    val documentId: DocumentId,
    val diffId: VersionDiffId,
    val fromVersionId: DocumentVersionId,
    val toVersionId: DocumentVersionId,
    val substantiveChanges: Int,
    val occurredAt: Instant,
)
