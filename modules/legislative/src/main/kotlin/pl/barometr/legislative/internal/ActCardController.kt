package pl.barometr.legislative.internal

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.barometr.legislative.api.ActId
import pl.barometr.shared.Eli
import java.util.UUID

/**
 * One published act: what it is, when it starts applying, what it changed, what has
 * changed it, and which draft it was.
 *
 * Every other part of this product leads here and, until now, nowhere: a search hit is
 * an act, a profile watches an act by its address, an alert cites one. This is where
 * those land.
 *
 * Two ways in because the two are how acts are actually named. The identifier is what
 * a search result carries; the address is what a person pastes out of a footnote, what
 * a profile stores, and what another register cites — and it is the same act either
 * way.
 *
 * Any authenticated caller may read it: this is the product's own record of public law,
 * and the operator role guards what spends somebody else's resources or decides what a
 * law *is*, which this does neither of.
 */
@RestController
@RequestMapping("/api/v1/legislative/acts")
class ActCardController(private val cards: ActCards) {

    @GetMapping("/{id}")
    fun act(@PathVariable id: UUID): ActCardResponse = describe(cards.cardFor(ActId(id)))

    /**
     * `DU/2024/1222` is three path segments, not one — an ELI has slashes in it, and
     * asking a client to escape them is asking for the half of them that will not.
     */
    @GetMapping("/eli/{publisher}/{year}/{position}")
    fun actAt(
        @PathVariable publisher: String,
        @PathVariable year: Int,
        @PathVariable position: Int,
    ): ActCardResponse {
        val address = "${publisher.uppercase()}/$year/$position"
        val eli = Eli.parseOrNull(address) ?: throw InvalidActAddressException(address)

        return describe(cards.cardFor(eli))
    }

    private fun describe(card: ActCard) = ActCardResponse(
        id = card.act.id.value,
        eli = card.act.eli.value,
        title = card.act.title,
        type = card.act.type,
        publisher = card.act.publisher,
        announcedOn = card.act.announcedOn?.toString(),
        // Named for what it is — the day it starts applying — rather than "in force",
        // which is a legal conclusion this system does not draw. See `ActCard`.
        appliesFrom = card.act.inForceFrom?.toString(),
        vacatioLegisDays = card.vacatioLegisDays,
        changes = card.changes.map(::describe),
        changedBy = card.changedBy.map(::describe),
        identifiers = card.identifiers.map {
            IdentifierResponse(it.scheme.wireName, it.value, it.resolvedBy)
        },
        draftId = card.draft?.value,
        latestChange = card.latestChange?.let { diff ->
            TextChangeResponse(
                documentId = diff.documentId.value,
                changes = diff.changeCount,
                substantiveChanges = diff.substantiveChanges,
                comparedAt = diff.computedAt.toString(),
            )
        },
    )

    private fun describe(citation: ActCitation) = CitationResponse(
        eli = citation.eli.value,
        relation = citation.relation.wireName,
        // Null when the archive does not hold the other side, which is ordinary: the
        // register cites acts from decades this ingestion never reached.
        id = citation.act?.value,
        title = citation.title,
        announcedOn = citation.announcedOn?.toString(),
    )

    data class CitationResponse(
        val eli: String,
        val relation: String,
        val id: UUID?,
        val title: String?,
        val announcedOn: String?,
    )

    data class IdentifierResponse(val scheme: String, val value: String, val resolvedBy: String)

    /**
     * What changed when the journal last published a new text of this act, and where to
     * read it: `GET /api/v1/corpus/documents/{documentId}/changes` pages the changes
     * themselves. The counts are here because they are the whole of what a card shows —
     * a redrafted act has thousands of changes and loading them to count them is the
     * read that would make this page slow.
     *
     * `substantiveChanges` is the number worth reading: a consolidated text moves
     * hundreds of units that say the same thing, and the ones that do not are what a
     * reader is looking for.
     */
    data class TextChangeResponse(
        val documentId: UUID,
        val changes: Int,
        val substantiveChanges: Int,
        val comparedAt: String,
    )

    data class ActCardResponse(
        val id: UUID,
        val eli: String,
        val title: String,
        val type: String,
        val publisher: String,
        val announcedOn: String?,
        val appliesFrom: String?,
        val vacatioLegisDays: Long?,
        val changes: List<CitationResponse>,
        val changedBy: List<CitationResponse>,
        val identifiers: List<IdentifierResponse>,
        val draftId: UUID?,
        /** Null for an act with one text, which is most of them. */
        val latestChange: TextChangeResponse?,
    )
}
