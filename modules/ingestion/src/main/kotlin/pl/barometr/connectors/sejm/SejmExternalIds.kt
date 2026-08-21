package pl.barometr.connectors.sejm

import pl.barometr.ingestion.api.ExternalId
import java.time.LocalDate

/**
 * How a Sejm entity is addressed in our archive.
 *
 * In one place because these strings are a contract, not formatting: they are the
 * idempotency key, so changing one silently re-ingests every document of that kind
 * as if it were new. Built here rather than inline at six call sites, where a
 * single divergent `"term$term/print/$n"` would be invisible.
 */
object SejmExternalIds {

    fun print(term: Int, number: String): ExternalId = ExternalId("term$term/print/$number")

    fun club(term: Int, symbol: String): ExternalId = ExternalId("term$term/club/$symbol")

    fun member(term: Int, id: String): ExternalId = ExternalId("term$term/mp/$id")

    fun proceeding(term: Int, number: Int): ExternalId = ExternalId("term$term/proceeding/$number")

    /**
     * A sitting the API has not numbered, addressed by the first day it sits.
     *
     * Flat rather than `term10/proceeding/date/2025-08-06`, and the reason is the
     * completeness audit: it counts documents *directly* under
     * `term{n}/proceeding/`, which is how a sitting's votings are kept out of the
     * count of sittings. A second slash here would hide every unnumbered sitting from
     * a total the source states as including them, and a healthy archive would report
     * a permanent fifteen-percent gap.
     *
     * A date cannot be mistaken for a number, so the two shapes share one prefix
     * without ambiguity.
     */
    fun proceedingOn(term: Int, firstDate: LocalDate): ExternalId =
        ExternalId("term$term/proceeding/$firstDate")

    /**
     * A legislative process — the passage of one draft, with its stages.
     *
     * Addressed by the print number it carries, which is also how the Sejm refers to
     * it, so `term10/print/424` and `term10/process/424` are the same draft seen as a
     * document and as a passage.
     */
    fun process(term: Int, number: String): ExternalId = ExternalId("term$term/process/$number")

    fun voting(term: Int, proceeding: Int, votingNumber: String): ExternalId =
        ExternalId("term$term/proceeding/$proceeding/voting/$votingNumber")

    // Prefixes for counting an archive. Stated outright rather than derived from an
    // id by string surgery, which is how `removeSuffix("0")` ended up in the
    // completeness check.
    fun printPrefix(term: Int): String = "term$term/print/"

    fun proceedingPrefix(term: Int): String = "term$term/proceeding/"

    fun processPrefix(term: Int): String = "term$term/process/"
}
