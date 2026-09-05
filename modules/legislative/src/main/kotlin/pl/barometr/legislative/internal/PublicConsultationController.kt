package pl.barometr.legislative.internal

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.barometr.legislative.api.ConsultationCalendar
import pl.barometr.legislative.api.ConsultationDeadline
import pl.barometr.shared.WorkingDays
import java.time.Clock
import java.time.LocalDate

/**
 * What is out for public comment, for anybody at all.
 *
 * The first route of the public API, and the right one to start with: a consultation
 * deadline is a fact about a public process that a ministry has already published, and
 * the only work this system has done to it is reading a date out of a PDF and counting
 * working days. Keeping that behind a login would be keeping back somebody else's
 * information.
 *
 * **The module owns the route; the application decides what it costs.** Rate limiting,
 * keys and tiers are applied by a filter in `:app`, for the same reason the security
 * chain lives there — only the application knows every context's routes. What this
 * controller knows is consultations.
 *
 * Both shapes serve the same window: JSON for a client asking about the next few weeks,
 * CSV for somebody loading the lot into a spreadsheet — which is what "open data" means
 * to most of the people who ask for it, and it is the shape a bulk scope exists for.
 */
@RestController
@RequestMapping("/api/v1/public/consultations")
class PublicConsultationController(
    private val calendar: ConsultationCalendar,
    private val clock: Clock,
) {

    @GetMapping
    fun open(): PublicConsultationsResponse {
        val today = LocalDate.now(clock)
        val open = calendar.closingBetween(today, today.plusDays(WINDOW_DAYS))

        return PublicConsultationsResponse(
            from = today.toString(),
            until = today.plusDays(WINDOW_DAYS).toString(),
            attribution = ATTRIBUTION,
            consultations = open.map { describe(it, today) },
        )
    }

    /**
     * The same window as a spreadsheet.
     *
     * Written by hand rather than with a library, and that is a deliberate exception to
     * this codebase's rule about not writing plumbing: the whole of CSV that matters here
     * is quoting a field that contains a quote or a comma, which is four lines, and the
     * alternative is a dependency for four lines.
     */
    @GetMapping(value = ["/csv"], produces = ["text/csv;charset=UTF-8"])
    fun csv(): String {
        val today = LocalDate.now(clock)

        return buildString {
            appendLine(HEADERS.joinToString(","))
            calendar.closingBetween(today, today.plusDays(WINDOW_DAYS)).forEach { deadline ->
                appendLine(
                    listOf(
                        deadline.id.value.toString(),
                        deadline.draftId.value.toString(),
                        deadline.draftTitle,
                        deadline.opensOn?.toString().orEmpty(),
                        deadline.closesOn.toString(),
                        WorkingDays.between(today, deadline.closesOn).toString(),
                        deadline.submissionAddress.orEmpty(),
                    ).joinToString(",", transform = ::quote),
                )
            }
        }
    }

    private fun describe(deadline: ConsultationDeadline, today: LocalDate) = PublicConsultationResponse(
        id = deadline.id.value.toString(),
        draftId = deadline.draftId.value.toString(),
        title = deadline.draftTitle,
        opensOn = deadline.opensOn?.toString(),
        closesOn = deadline.closesOn.toString(),
        workingDaysLeft = WorkingDays.between(today, deadline.closesOn),
        submissionAddress = deadline.submissionAddress,
        quote = deadline.quote,
    )

    /** RFC 4180: a field containing a quote, a comma or a newline is quoted, and quotes are doubled. */
    private fun quote(value: String): String =
        if (value.any { it == '"' || it == ',' || it == '\n' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }

    data class PublicConsultationsResponse(
        val from: String,
        val until: String,
        /** What anybody republishing this is asked to say. Part of the data, not a footnote. */
        val attribution: String,
        val consultations: List<PublicConsultationResponse>,
    )

    data class PublicConsultationResponse(
        val id: String,
        val draftId: String,
        val title: String,
        val opensOn: String?,
        val closesOn: String,
        /** Counted in days somebody can actually file on, holidays included. */
        val workingDaysLeft: Int,
        val submissionAddress: String?,
        /** The ministry's own sentence, so a reader who doubts the date can check it. */
        val quote: String,
    )

    private companion object {
        /** A quarter ahead: what a calendar shows, and further than any consultation period runs. */
        const val WINDOW_DAYS = 90L

        const val ATTRIBUTION = "Źródło: Barometr (barometr.example), na podstawie RPL"

        val HEADERS = listOf(
            "id",
            "draft_id",
            "title",
            "opens_on",
            "closes_on",
            "working_days_left",
            "submission_address",
        )
    }
}
