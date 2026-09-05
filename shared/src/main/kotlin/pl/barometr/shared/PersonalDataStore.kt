package pl.barometr.shared

import java.util.UUID

/**
 * A context that holds data about a person, and can hand it over or erase it.
 *
 * **Implemented, never called, across a boundary.** Every context that stores anything
 * about an account implements this; one orchestrator collects the implementations and
 * asks them all. That is why the interface lives here rather than in anybody's `api`
 * package: a port in identity's contract would make every other context depend on
 * identity, and a port in each context would make the orchestrator depend on all of
 * them. This way the dependency is on a value type nobody owns.
 *
 * **A context that holds nothing does not implement it.** The absence is the statement:
 * corpus holds documents, legislative holds bills, and neither knows a person exists.
 *
 * The rights this serves have statutory deadlines, so both methods are expected to be
 * fast and neither may be best-effort: an erasure that partly worked is a breach with
 * paperwork.
 */
interface PersonalDataStore {

    /** The context's name, as it appears in an export. */
    val category: String

    /** Everything held about this account, in a form a person can read. */
    fun personalDataOf(user: UUID): PersonalDataExtract

    /**
     * Erases what may be erased, and reports what survived and why.
     *
     * Called inside one transaction with every other store, so a failure anywhere leaves
     * the account intact rather than half-deleted.
     */
    fun erasePersonalData(user: UUID): ErasureReport
}
