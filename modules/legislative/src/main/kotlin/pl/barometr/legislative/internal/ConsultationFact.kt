package pl.barometr.legislative.internal

import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentVersionId
import java.time.LocalDate

/**
 * What one document turned out to state about when comments are due, in the shape the
 * consultation row is written from.
 *
 * The three requirements below are the table's three `CHECK` constraints said again in
 * Kotlin. Deliberately said twice: the database is where the invariant is enforced, and
 * a listener that hands it a contradiction gets an exception thrown from inside a
 * transaction and redelivered forever. Failing here fails at the letter that caused it,
 * with the letter still in the message.
 *
 * [statedIn] is the document and [statedBy] the version of it. Both, because a later
 * version of the same document is a ministry correcting its own letter and replaces
 * this, while a different document restating the date does not.
 */
data class ConsultationFact(
    val opensOn: LocalDate?,
    val closesOn: LocalDate,
    val daysAllowed: Int?,
    val submissionAddress: String?,
    val quote: String,
    val charStart: Int,
    val charEnd: Int,
    val statedIn: DocumentId,
    val statedBy: DocumentVersionId,
) {
    init {
        require(daysAllowed == null || opensOn != null) {
            "A period of $daysAllowed days runs from a day, and this letter has none"
        }
        require(opensOn == null || !closesOn.isBefore(opensOn)) {
            "Comments would close on $closesOn, before the letter of $opensOn asked for them"
        }
        require(charEnd > charStart) { "A citation spans characters, got $charStart..$charEnd" }
    }
}
