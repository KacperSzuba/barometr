package pl.barometr.corpus.internal.diff

import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.shared.ContentHash

/**
 * Two versions of one document that can be compared: both are in the archive and both
 * have their text.
 *
 * The text hashes travel with the pair because they are what the comparison reads —
 * the extracted text in the derived bucket, the same characters every offset in the
 * result indexes. A version whose extraction has not run, or whose payload was a scan
 * with no text layer, never becomes a pair: there is nothing to compare, and saying
 * "everything was removed" about it would be a lie the archive itself contradicts.
 */
data class ComparablePair(
    val documentId: DocumentId,
    val fromVersionId: DocumentVersionId,
    val fromTextHash: ContentHash,
    val toVersionId: DocumentVersionId,
    val toTextHash: ContentHash,
)
