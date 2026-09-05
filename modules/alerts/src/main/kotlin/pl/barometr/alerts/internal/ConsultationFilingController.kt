package pl.barometr.alerts.internal

import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.barometr.identity.api.callerOf
import pl.barometr.legislative.api.ConsultationId
import java.security.Principal
import java.util.UUID

/**
 * "We have written in about this one."
 *
 * The state that makes a deadline a task rather than a notice: once it is set, the
 * consultation leaves the calendar feed, because a deadline that has been met is not a
 * deadline and a calendar that keeps showing it teaches its reader to ignore it.
 *
 * `PUT` rather than `POST`: pressing the button twice is somebody confirming what they
 * already said, and the second press updates the note rather than failing.
 */
@RestController
@RequestMapping("/api/v1/alerts/consultations")
class ConsultationFilingController(private val filings: ConsultationFilingRepository) {

    @PutMapping("/{id}/filing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun recordFiling(
        caller: Principal,
        @PathVariable id: UUID,
        @Valid @RequestBody(required = false) filing: FilingRequest?,
    ) {
        filings.recordFiling(callerOf(caller), ConsultationId(id), filing?.note?.takeIf(String::isNotBlank))
    }

    /** Withdrawing one that was never recorded is not an error: both mean "nothing filed". */
    @DeleteMapping("/{id}/filing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun withdrawFiling(caller: Principal, @PathVariable id: UUID) {
        filings.withdrawFiling(callerOf(caller), ConsultationId(id))
    }

    @GetMapping("/filings")
    fun filings(caller: Principal): List<FilingResponse> =
        filings.filings(callerOf(caller)).map {
            FilingResponse(
                consultationId = it.consultation.value,
                filedAt = it.filedAt.toString(),
                note = it.note,
            )
        }

    data class FilingRequest(
        /** Bounded by the `CHECK` on the column, so both refuse the same thing. */
        @field:Size(max = 500)
        val note: String? = null,
    )

    data class FilingResponse(val consultationId: UUID, val filedAt: String, val note: String?)
}
