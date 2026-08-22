package pl.barometr.legislative.internal

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.LegislativeCatalog
import pl.barometr.legislative.api.PublishedAct
import pl.barometr.shared.Eli
import java.time.temporal.ChronoUnit

/**
 * Assembles one act's card.
 *
 * Computed per request rather than kept as a read model: it is four indexed lookups for
 * one act, which is cheaper than any staleness would be worth.
 *
 * Reads the act through this context's own published port. That port is already the one
 * definition of what an act looks like outside its tables, and a second query here
 * would be the same fact stated twice — free to disagree the day either changes.
 */
@Service
@Transactional(readOnly = true)
class ActCards(
    private val catalog: LegislativeCatalog,
    private val references: ActReferenceRepository,
    private val identifiers: ActIdentifierRepository,
    private val drafts: DraftRepository,
) {

    fun cardFor(id: ActId): ActCard =
        cardFor(catalog.actById(id) ?: throw UnknownActException(id.toString()))

    /**
     * By address, because that is how an act is quoted everywhere else: a profile
     * watches `DU/2024/1222`, an alert cites it, and a person pastes it out of a
     * footnote.
     */
    fun cardFor(eli: Eli): ActCard =
        cardFor(catalog.actByEli(eli) ?: throw UnknownActException(eli.value))

    private fun cardFor(act: PublishedAct) = ActCard(
        act = act,
        vacatioLegisDays = vacatioLegisOf(act),
        changes = references.changesMadeBy(act.eli),
        changedBy = references.changesMadeTo(act.eli),
        identifiers = identifiers.identifiersOf(act.id),
        draft = drafts.draftBecoming(act.id),
    )

    /**
     * Null rather than zero when either date is missing.
     *
     * Zero is a real answer — an act that applies on the day it is announced — and
     * reporting it for one we simply do not have the dates for would be inventing the
     * most consequential number on the card.
     */
    private fun vacatioLegisOf(act: PublishedAct): Long? {
        val announced = act.announcedOn ?: return null
        val applies = act.inForceFrom ?: return null

        return ChronoUnit.DAYS.between(announced, applies)
    }
}
