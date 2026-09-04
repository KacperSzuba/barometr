package pl.barometr.corpus.api

import pl.barometr.ingestion.api.ExternalId
import pl.barometr.shared.ContentHash

/**
 * The newest version of an archived document, and the two hashes anything deriving
 * from it reads by.
 *
 * What [DocumentTextExtracted] carries, answered on demand rather than announced. A
 * consumer that followed the events has no use for this; one that is going back over
 * the archive — because it was written after the text was extracted, or because it
 * missed something — has no other way in, since an event fires once and is not
 * repeated on request.
 *
 * [textHash] is null while the version has no text: a scan with no text layer, or a
 * payload whose extraction has not run yet. Both mean the same thing to a caller — come
 * back later, and read the original if you must.
 */
data class ArchivedVersion(
    val documentId: DocumentId,
    /**
     * The address the source knows it by, carried so that a caller enumerating the
     * archive can tell what it is holding — for several sources the address is the only
     * place a document's shape is stated at all.
     */
    val externalId: ExternalId,
    val versionId: DocumentVersionId,
    val contentHash: ContentHash,
    val textHash: ContentHash?,
)
