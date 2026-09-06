package pl.barometr.legislative.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.connectors.rcl.api.RclPageReader
import pl.barometr.connectors.rcl.api.RclProjectCard
import pl.barometr.connectors.rcl.api.RclStageState
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.storage.BlobBucket
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.DraftRecorded
import pl.barometr.storage.BlobStore
import java.time.Clock
import java.time.ZoneOffset

/**
 * Turns an archived RPL card into a draft, months before the Sejm has heard of it.
 *
 * That head start is the point: a government bill spends its first half-year in
 * consultation, and knowing it exists — its title, its ministry, the number people
 * quote it by — is most of what this product promises before the Sejm ever prints it.
 *
 * **One stage is recorded here, and it is deliberately a coarse one.** A card is a
 * checklist: eight stages with a state each — not started, current, done — and at most
 * a "last modified" stamp on the few that have moved. That stamp is not the day a
 * stage began, and `stage_transition` exists to answer what the status was on a given
 * day, so the per-stage timeline is read from the change registers instead, by
 * [RclChangeRegisterProjector], which is the only page on this source that times a move
 * to the minute. What the card does state is the day the draft entered the process, and
 * that one dated fact is what gives a government draft a position in time months before
 * the Sejm has heard of it — and before its register has been read.
 *
 * **The consultation it opens is the one thing here a reader can still act on.** A card
 * showing a public-consultation stage means comments are being taken; when they close
 * is not on the card, so an empty row is opened for the letter filed under that stage
 * to fill in — see [ConsultationOpening].
 *
 * **A draft here and the same draft in the Sejm are two rows, joined rather than
 * merged.** Neither register prints the other's number in a form the other shows — the
 * Sejm knows `RM-0610-102-23`, the card shows `12409051` and `UD383` — so the numbers
 * recorded here are what [DraftIdentityMatcher] joins the two by when they happen to
 * coincide, and a title comparison is what it falls back on when they do not. The pair
 * is recorded in `draft_continuation` and each row keeps its own id, because by the
 * time the join can be made at all, alerts and the search index are holding both.
 */
@Service
class RclCardProjector(
    private val blobs: BlobStore,
    private val pages: RclPageReader,
    private val drafts: DraftRepository,
    private val identifiers: DraftIdentifierRepository,
    private val transitions: StageTransitionRepository,
    private val consultations: ConsultationOpening,
    private val events: ApplicationEventPublisher,
    private val meters: MeterRegistry,
    private val clock: Clock,
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

        record(card, recorded.versionId, recorded.externalId.value)
    }

    private fun record(card: RclProjectCard, statedBy: DocumentVersionId, cardAddress: String) {
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
        recordEntryIntoTheProcess(draftId, card, statedBy)

        // The one stage a reader can still act on. Opened from the card because the
        // card is where a consultation is announced; when it closes is read later, out
        // of the letter filed under it.
        consultations.openConsultationsOnCard(draftId, card, cardAddress)
        // Read, and recorded as read: the sweep over archived cards exists for the ones
        // this listener never sees, and re-parsing a card it has just handled would be
        // work for an answer already given.
        drafts.markConsultationsReadFromCard(draftId)

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

        events.publishEvent(DraftRecorded(draftId, clock.instant()))
        log.debug("Recorded government draft {} from RPL", card.projectId)
    }

    /**
     * The draft has been in the government's process since the day RPL created it,
     * and — as far as this source can say — still is.
     *
     * An open period, because a card never states that a draft left: it leaves by
     * arriving in the Sejm, which is a different register saying so — and
     * [GovernmentProcessClosure] is where that word is taken, once the two registers
     * have been joined.
     */
    private fun recordEntryIntoTheProcess(draftId: DraftId, card: RclProjectCard, statedBy: DocumentVersionId) {
        val started = card.createdOn ?: return

        transitions.recordFacts(
            draftId = draftId,
            facts = listOf(
                StageFact(
                    stage = LegislativeStage.GOVERNMENT_PROCESS,
                    from = started.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    until = null,
                    ordinal = 0,
                    // The stage the card marks as current, in RPL's own words. The
                    // model is coarser than the label on purpose; the label is what
                    // makes the difference visible.
                    sourceLabel = card.stages.firstOrNull { it.state == RclStageState.CURRENT }?.name
                        ?: PROCESS_LABEL,
                    isException = false,
                ),
            ),
            statedBy = statedBy,
            knownAt = clock.instant(),
        )
    }

    private companion object {
        val PROJECT = DocumentKind("rcl-project")

        /** Used only when the card marks no stage as current. */
        const val PROCESS_LABEL = "Rządowy proces legislacyjny"
    }
}
