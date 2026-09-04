package pl.barometr.legislative.api

import java.time.LocalDate

/**
 * Read port over what is out for comment and until when.
 *
 * A window rather than a page: consultations open at any moment number in the
 * hundreds, and every caller — a calendar feed, the run that warns somebody three days
 * out — asks the same question about a range of days. Keyset paging would be
 * machinery for a limit nothing reaches.
 */
interface ConsultationCalendar {

    /** Consultations closing between the two days inclusive, soonest first. */
    fun closingBetween(from: LocalDate, until: LocalDate): List<ConsultationDeadline>

    /** One consultation, by its identity. Null when there is no such row. */
    fun consultationById(id: ConsultationId): ConsultationDeadline?
}
