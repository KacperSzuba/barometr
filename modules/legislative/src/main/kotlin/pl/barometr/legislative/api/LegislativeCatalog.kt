package pl.barometr.legislative.api

import pl.barometr.shared.Eli

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

    /**
     * The act at that address, which is how everything outside this context names one:
     * an ELI is what a person quotes and what another register cites, while [ActId] is
     * ours and means nothing to them.
     */
    fun actByEli(eli: Eli): PublishedAct?

    fun draftById(id: DraftId): TrackedDraft?

    /**
     * The evidence for how much this draft matters, or null when nothing is recorded
     * about where it stands.
     *
     * A read of its own rather than two more fields on [TrackedDraft], because the two
     * answer different questions: a draft is what it is called and where it is, and
     * this is what somebody ranking a list needs on top of that. Search indexes the
     * first and has no use for the second.
     */
    fun signalsForDraft(id: DraftId): LegislativeSignals?

    /** Acts after [after], oldest first. Null starts from the beginning. */
    fun actsAfter(after: ActId?, limit: Int): List<PublishedAct>

    fun draftsAfter(after: DraftId?, limit: Int): List<TrackedDraft>
}
