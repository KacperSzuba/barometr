package pl.barometr.legislative.internal

import pl.barometr.legislative.api.DraftId
import java.time.LocalDate

/**
 * Everything needed to decide whether two draft rows are the same draft: the numbers
 * it is quoted by, the title, and the day its register says it began.
 *
 * The title is carried already normalised because that is what a similarity is
 * measured against, and normalising it twice — once for storage, once for a query —
 * is how the two quietly stop matching.
 */
data class DraftIdentity(
    val id: DraftId,
    val title: String,
    val normalisedTitle: String,
    val startedOn: LocalDate?,
    val identifiers: List<DraftIdentifierValue>,
) {

    /**
     * Which register wrote this row, or null when the answer is not one register.
     *
     * Null covers both ends of that: a draft claimed under neither register's key is
     * not a draft either register knows, and one claimed under both is already the
     * joined row this whole mechanism exists to produce.
     */
    val register: DraftRegister? get() = DraftRegister.entries
        .filter { register -> identifiers.any { it.scheme == register.claimedBy } }
        .singleOrNull()

    /**
     * The numbers that could conceivably appear on the other side.
     *
     * Only two of the four schemes can: the Council of Ministers' number is what the
     * Sejm's register prints when it points back at RPL, and the programme-of-work
     * number is what a card shows. RPL's project id keys its own URLs and appears
     * nowhere else, so including it would only widen the search with values that
     * cannot match — and a value that cannot match is a collision waiting to be a
     * wrong join.
     */
    val sharedNumbers: List<String> get() = identifiers
        .filter { it.scheme in SHARED_SCHEMES }
        .map { it.value }

    private companion object {
        val SHARED_SCHEMES = setOf(
            DraftIdentifierScheme.COUNCIL_OF_MINISTERS,
            DraftIdentifierScheme.PROGRAMME_OF_WORK,
        )
    }
}
