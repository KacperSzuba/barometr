package pl.barometr.legislative.internal

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.barometr.legislative.api.ConsultationCalendar
import pl.barometr.legislative.api.ConsultationDeadline
import pl.barometr.legislative.api.ConsultationId
import pl.barometr.shared.WorkingDays
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * What is out for public comment, and until when.
 *
 * Any authenticated caller may read it, for the reason the draft card gives: this is
 * the product's own reading of a public process, and the operator role guards what
 * spends resources or decides what a law is.
 *
 * Every entry carries the ministry's own sentence and the working days still left in
 * it. The second is the number a reader actually acts on — "three days" means three
 * chances to file, and a count of calendar days spanning a long weekend would mean one.
 */
@RestController
@RequestMapping("/api/v1/legislative/consultations")
class ConsultationCalendarController(
    private val calendar: ConsultationCalendar,
    private val clock: Clock,
) {

    @GetMapping
    fun closing(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        from: LocalDate?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        until: LocalDate?,
    ): ConsultationsResponse {
        val today = LocalDate.now(clock)
        val start = from ?: today
        val end = until ?: start.plusDays(DEFAULT_WINDOW_DAYS)

        if (end.isBefore(start)) {
            throw InvalidConsultationWindowException("$start..$end runs backwards")
        }
        if (start.plusDays(MAX_WINDOW_DAYS).isBefore(end)) {
            throw InvalidConsultationWindowException("$start..$end is longer than $MAX_WINDOW_DAYS days")
        }

        return ConsultationsResponse(
            from = start.toString(),
            until = end.toString(),
            consultations = calendar.closingBetween(start, end).map { describe(it, today) },
        )
    }

    @GetMapping("/{id}")
    fun consultation(@PathVariable id: UUID): ConsultationResponse =
        calendar.consultationById(ConsultationId(id))
            ?.let { describe(it, LocalDate.now(clock)) }
            ?: throw UnknownConsultationException(id.toString())

    private fun describe(deadline: ConsultationDeadline, today: LocalDate) = ConsultationResponse(
        id = deadline.id.value,
        draftId = deadline.draftId.value,
        draftTitle = deadline.draftTitle,
        opensOn = deadline.opensOn?.toString(),
        closesOn = deadline.closesOn.toString(),
        workingDaysLeft = WorkingDays.between(today, deadline.closesOn),
        daysAllowed = deadline.daysAllowed,
        submissionAddress = deadline.submissionAddress,
        quote = deadline.quote,
    )

    data class ConsultationsResponse(
        /** Echoed back because both ends of the window have defaults. */
        val from: String,
        val until: String,
        val consultations: List<ConsultationResponse>,
    )

    data class ConsultationResponse(
        val id: UUID,
        val draftId: UUID,
        val draftTitle: String,
        val opensOn: String?,
        val closesOn: String,
        /** Zero once the day is past: there is no negative number of chances to file. */
        val workingDaysLeft: Int,
        /** Set when the ministry stated a period rather than a date. */
        val daysAllowed: Int?,
        val submissionAddress: String?,
        /** The ministry's own words, so a reader who doubts the date can check it. */
        val quote: String,
    )

    private companion object {
        const val DEFAULT_WINDOW_DAYS = 30L

        /**
         * A quarter. Not a limit of the query — the index answers any range — but of
         * what a single response should carry, since every open consultation in the
         * country closes within about two months of the day it opened.
         */
        const val MAX_WINDOW_DAYS = 92L
    }
}
