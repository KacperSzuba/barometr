package pl.barometr.legislative.internal

import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.PublishedAct

/**
 * One published act, answered the way a reader arrives at it: from a search hit, from
 * an alert, or from the draft they have been following.
 *
 * **There is no "in force" field, and its absence is the considered part.** Whether an
 * act applies today is a legal conclusion, not a date comparison: acts are repealed in
 * part, suspended, amended into something else, and given transitional provisions that
 * outlive them. What is here instead is what the register itself states — the day it
 * starts applying, and every act recorded as changing or repealing it — so a reader
 * draws that conclusion with the evidence in front of them rather than being handed
 * ours.
 */
data class ActCard(
    val act: PublishedAct,
    /**
     * Days between announcement and application, when both are known.
     *
     * A hard number, computed from two dates the journal states — never an estimate,
     * and named so it cannot be confused with one.
     */
    val vacatioLegisDays: Long?,
    /** What this act does to others. */
    val changes: List<ActCitation>,
    /** What others do to it, newest first — the direction that says whether it moved. */
    val changedBy: List<ActCitation>,
    /** The numbers it is quoted by: the print, the government's programme number. */
    val identifiers: List<ActIdentifierValue>,
    /** The draft it was, when the identity matching found one. Closes the loop. */
    val draft: DraftId?,
)
