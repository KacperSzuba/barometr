package pl.barometr.corpus.api

import pl.barometr.ingestion.api.ExternalId

/**
 * Read port over the corpus. Nothing outside this context touches its tables.
 *
 * Deliberately narrow. Identity resolution needs to look at the document an event
 * just announced — its title, the day it was issued — and reporting needs to know how
 * many documents of each kind exist, because "the share of documents pinned to an act"
 * cannot be computed inside the context that holds only the pinning half of it.
 * Everything else a document *says* is read out of the archive by whoever needs it,
 * from the hashes below.
 *
 * The third method is the way back in for a consumer that cannot follow an event:
 * events fire once, and a derivation written after the text was extracted — or one
 * that dropped something — has no way to ask for them again.
 */
interface DocumentCatalog {

    fun documentById(id: DocumentId): ArchivedDocument?

    /**
     * The newest version archived at this address, or null when nothing was.
     *
     * By address rather than by identity because that is what a caller re-deriving
     * from the archive holds: the id a source knows a document by is reconstructible
     * from what the source itself published, while the corpus's own identifier is not.
     *
     * Addresses are unique per source in the schema rather than globally, and a
     * caller here has no source to name. In practice each source's addresses carry
     * its own scheme — `projekt/…`, `term10/…`, an ELI — so the ambiguity is
     * theoretical; the oldest document holding the address answers, so that the same
     * question keeps giving the same answer if it ever stops being.
     */
    fun latestVersionAt(externalId: ExternalId): ArchivedVersion?

    /**
     * A page of what the archive holds of one kind, oldest first, each with its newest
     * version.
     *
     * For a derivation that has to go over the archive rather than wait at its edge —
     * one written after the documents it needs were already stored. Paged by identity
     * rather than by offset: the identifiers are time-ordered, so `after` walks the
     * whole kind in order and cannot skip a document stored while the walk was running.
     */
    fun versionsOfKind(kind: DocumentKind, after: DocumentId?, limit: Int): List<ArchivedVersion>

    /** How many documents the corpus holds of each kind. */
    fun countByKind(): Map<DocumentKind, Int>
}
