package pl.barometr.legislative.internal

import java.time.LocalDate

/**
 * The two ways a ministry states how long there is to comment.
 *
 * Sealed and kept apart all the way to the database, because they are different
 * claims. A date is the ministry's own arithmetic and this system only repeats it; a
 * period is a number that has to be counted from a day, and which day that is depends
 * on reading the letter's own dateline correctly. When the second goes wrong it goes
 * wrong quietly, so the row records which of the two was read and a reader can see the
 * working.
 */
sealed interface ConsultationTerm {

    /** "w terminie 21 dni" — counted from the day the letter went out. */
    data class Period(val days: Int) : ConsultationTerm {
        init {
            require(days > 0) { "A consultation period is a positive number of days, got $days" }
        }
    }

    /** "do dnia 15 marca 2026 r." — a day, stated. */
    data class ClosingDate(val on: LocalDate) : ConsultationTerm
}
