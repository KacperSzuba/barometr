package pl.barometr.taxonomy.internal

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.barometr.taxonomy.api.ClassifiedSubject
import java.util.UUID

/**
 * Which industries a law is in, for whoever is looking at it.
 *
 * The tags existed and could not be read. They could be written — an operator route
 * takes a batch of them — and everything inside this system routed on them: the profile
 * preview, the alert run, the coverage gauge. Nothing outside could ask, so a reader
 * shown an act had no way to see why it reached them, and a card had no way to show it.
 *
 * **Any authenticated caller may read it**, on the same reasoning [ActCardController]
 * and [DraftCardController] are read by one: this is the product's own description of a
 * public legislative process, and there is nothing here a signed-up account should not
 * see. What stays operator-only is deciding — recording a verdict rewrites what every
 * reader is told a law is about, and registration is open.
 *
 * **Accepted verdicts only**, which is the rule the published port states and this
 * repeats deliberately rather than widening: what a classifier was unsure about is a
 * question for a person, and showing it beside a tag somebody confirmed would make the
 * two look alike.
 */
@RestController
@RequestMapping("/api/v1/taxonomy/subjects")
@PreAuthorize("isAuthenticated()")
class IndustryLookupController(private val classifications: IndustryClassifications) {

    @GetMapping("/{kind}/{id}/industries")
    fun industries(@PathVariable kind: String, @PathVariable id: UUID): List<IndustryResponse> {
        // A kind outside the vocabulary is a caller's mistake, not an impossible state.
        val subject = runCatching { ClassifiedSubject(kind, id) }
            .getOrElse { throw InvalidIndustryException("subject kind '$kind'") }

        return classifications.industriesOf(subject).map { verdict ->
            IndustryResponse(
                pkd = verdict.code.value,
                decidedBy = verdict.method.wireName,
                matchedOn = verdict.matchedOn,
            )
        }
    }

    /**
     * The code, who decided it, and what caught it.
     *
     * No confidence: a number a reader cannot calibrate reads as precision this does
     * not have, and the distinction that matters to them is the one between a person
     * having decided and a reading having done so. The number is in the operator's
     * queue, where it is compared against a threshold.
     */
    data class IndustryResponse(
        val pkd: String,
        /** `manual` or `model`. */
        val decidedBy: String,
        /** The phrase a classifier matched; absent where a person decided. */
        val matchedOn: String?,
    )
}
