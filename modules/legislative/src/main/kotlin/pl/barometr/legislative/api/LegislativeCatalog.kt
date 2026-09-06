package pl.barometr.legislative.api

import pl.barometr.corpus.api.DocumentId
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

    /**
     * For each of these archived files, the draft it was filed under — omitting the ones
     * no draft names.
     *
     * The way in for anything holding a document rather than a draft: a consumer that
     * has read a file knows what it says and not what it is about, and this context
     * holds the only join between the two.
     *
     * A batch because of its one caller's shape: a page of search results is up to a
     * hundred documents, and the per-document version of this question is a hundred
     * round trips for one search. A file no draft names is simply absent from the
     * answer, which is the common case rather than a failure — most of the archive is
     * pages *about* drafts rather than files filed under one, and a file can reach the
     * archive before the card that creates the draft.
     */
    fun filedUnderDrafts(documentIds: List<DocumentId>): List<FiledUnderDraft>

    /** Acts after [after], oldest first. Null starts from the beginning. */
    fun actsAfter(after: ActId?, limit: Int): List<PublishedAct>

    fun draftsAfter(after: DraftId?, limit: Int): List<TrackedDraft>
}
