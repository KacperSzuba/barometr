package pl.barometr.legislative.api

import java.time.LocalDate

/**
 * Read port over what is out for comment and until when.
 *
 * A window rather than a page: consultations open at any moment number in the
 * hundreds, and the question asked of them is always about a range of days — which is
 * what the calendar endpoint asks today and what a run warning somebody three days out
 * would ask. Keyset paging would be machinery for a limit nothing reaches.
 */
interface ConsultationCalendar {

    /** Consultations closing between the two days inclusive, soonest first. */
    fun closingBetween(from: LocalDate, until: LocalDate): List<ConsultationDeadline>

    /**
     * One consultation, by its identity.
     *
     * Null both when there is no such consultation and when there is one no letter has
     * dated yet — the same rule the window answers by, for the same reason
     * [ConsultationDeadline] states.
     */
    fun consultationById(id: ConsultationId): ConsultationDeadline?
}
