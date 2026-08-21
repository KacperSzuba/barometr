package pl.barometr.corpus.api

/**
 * Read port over the corpus. Nothing outside this context touches its tables.
 *
 * Deliberately two methods. Identity resolution needs to look at the document an
 * event just announced — its title, the day it was issued — and reporting needs to
 * know how many documents of each kind exist, because "the share of documents
 * pinned to an act" cannot be computed inside the context that holds only the
 * pinning half of it. Everything else a document says is read out of the archive by
 * whoever needs it.
 */
interface DocumentCatalog {

    fun documentById(id: DocumentId): ArchivedDocument?

    /** How many documents the corpus holds of each kind. */
    fun countByKind(): Map<DocumentKind, Int>
}
