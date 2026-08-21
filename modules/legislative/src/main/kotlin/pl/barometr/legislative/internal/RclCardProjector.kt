package pl.barometr.legislative.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.connectors.rcl.api.RclPageReader
import pl.barometr.connectors.rcl.api.RclProjectCard
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore

/**
 * Turns an archived RPL card into a draft, months before the Sejm has heard of it.
 *
 * That head start is the point: a government bill spends its first half-year in
 * consultation, and knowing it exists — its title, its ministry, the number people
 * quote it by — is most of what this product promises before the Sejm ever prints it.
 *
 * **No stages are recorded here, and that is a fact about the source rather than a
 * gap in the work.** A card is a checklist: eight stages with a state each — not
 * started, current, done — and at most a "last modified" stamp on the few that have
 * moved. A last-modified stamp is not the day a stage began, and `stage_transition`
 * exists to answer what the status was on a given day. The dates that would answer it
 * are in the change registers, which the connector already archives and which state
 * events to the minute; reading them is the next piece of this, and until then the
 * honest record is a draft with a start date and no timeline.
 *
 * **A draft here and the same draft in the Sejm stay two records for now.** Neither
 * register prints the other's number in a form the other shows — the Sejm knows
 * `RM-0610-102-23`, the card shows `12409051` and `UD383` — so joining them means
 * following RPL's own resolver or matching titles, and both are their own piece of
 * work. The identifiers each register does state are recorded, which is what makes
 * that join possible later.
 */
@Service
class RclCardProjector(
    private val blobs: BlobStore,
    private val pages: RclPageReader,
    private val drafts: DraftRepository,
    private val identifiers: DraftIdentifierRepository,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun projectGovernmentDraft(recorded: DocumentVersionRecorded) {
        if (recorded.kind != PROJECT) return

        val payload = blobs.read(BlobBucket.RAW, recorded.contentHash)?.use { it.readBytes() }
        if (payload == null) {
            log.warn("No archived bytes for RPL card {} at {}", recorded.externalId, recorded.contentHash)
            return
        }

        val card = pages.readProjectCard(payload)
        if (card == null || card.title.isBlank()) {
            meters.counter("legislative.rcl_card.skipped", "reason", "unreadable").increment()
            return
        }

        record(card)
    }

    private fun record(card: RclProjectCard) {
        val draft = DraftFromRegister(
            title = card.title,
            // RPL is the government's own legislative process; everything on it is a
            // government draft, whichever ministry filed it.
            initiator = DraftInitiator.GOVERNMENT,
            term = card.termNumber,
            startedOn = card.createdOn,
        )

        val draftId = identifiers.draftFor(DraftIdentifierScheme.RCL_PROJECT, card.projectId)
            ?: drafts.insertDraft(draft).also {
                identifiers.claimForDraft(DraftIdentifierScheme.RCL_PROJECT, card.projectId, it)
            }

        drafts.restateDraft(draftId, draft)

        // The number a person actually quotes. Absent on a draft filed outside any
        // ministry's programme of work, which is why it is an alias and not the claim.
        card.registerNumber?.takeIf { it.isNotBlank() }?.let {
            identifiers.pointAtDraft(
                DraftIdentifierScheme.PROGRAMME_OF_WORK,
                it,
                draftId,
                MatchMethod.EXACT,
                confidence = 1.0,
            )
        }

        log.debug("Recorded government draft {} from RPL", card.projectId)
    }

    private companion object {
        val PROJECT = DocumentKind("rcl-project")
    }
}
