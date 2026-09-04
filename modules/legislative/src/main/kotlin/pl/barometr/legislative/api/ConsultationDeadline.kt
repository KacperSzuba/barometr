package pl.barometr.legislative.api

import java.time.LocalDate

/**
 * A consultation with a date on it, as other contexts see one.
 *
 * Only the ones a date could be read for cross this boundary. A consultation whose
 * letter has not been filed or could not be read is a real thing this system knows
 * about and has nothing to tell anybody about yet, and putting it in a calendar with
 * a guessed deadline is precisely the failure the whole provenance chain exists to
 * prevent.
 *
 * [quote] travels with it for the same reason: whoever renders this — a calendar
 * entry, a reminder three days out — can show the ministry's own sentence beside the
 * date, so a reader who doubts it can check rather than write in.
 */
data class ConsultationDeadline(
    val id: ConsultationId,
    val draftId: DraftId,
    val draftTitle: String,
    /** The day the letter went out, and the day any stated period runs from. */
    val opensOn: LocalDate?,
    /** The day comments are due, already moved off a Saturday or a statutory day off. */
    val closesOn: LocalDate,
    /** Set when the ministry stated a period rather than a date. */
    val daysAllowed: Int?,
    /** Named by the letter, or null — which is the usual case, and not a gap to fill in. */
    val submissionAddress: String?,
    val quote: String,
)
