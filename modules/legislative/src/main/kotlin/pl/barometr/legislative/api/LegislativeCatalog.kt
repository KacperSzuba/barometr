package pl.barometr.legislative.api

/**
 * Read port over acts and drafts. Nothing outside legislative touches its tables.
 *
 * The paged methods exist for one caller with one need: a derived index has to be
 * rebuildable from Postgres, and rebuilding it means walking everything without
 * holding a hundred thousand acts in memory. Keyset paging on the identifier rather
 * than an offset, because the identifiers are time-ordered and an offset over a
 * growing table skips rows.
 */
interface LegislativeCatalog {

    fun actById(id: ActId): PublishedAct?

    fun draftById(id: DraftId): TrackedDraft?

    /** Acts after [after], oldest first. Null starts from the beginning. */
    fun actsAfter(after: ActId?, limit: Int): List<PublishedAct>

    fun draftsAfter(after: DraftId?, limit: Int): List<TrackedDraft>
}
