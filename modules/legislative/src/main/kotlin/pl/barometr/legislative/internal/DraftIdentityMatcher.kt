package pl.barometr.legislative.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.DraftRecorded

/**
 * Joins the government's draft to the print it became, so a reader following one of
 * them stops losing the half of the story the other register holds.
 *
 * The same draft is two rows here — `12409051` in RPL from the day a ministry filed
 * it, `term10/print/424` in the Sejm six months later — because neither register
 * prints a number the other shows. That gap is the product's own promise turned
 * inside out: consultation is where a draft can still be influenced, and a reader who
 * finds it only once it reaches the Sejm has found it too late to act.
 *
 * Three outcomes, deliberately the same three [ActIdentityMatcher] has one level up. A
 * number both registers quote ends the matter. A title close enough is taken, or —
 * closer than noise but not close enough to trust — handed to a person. Anything
 * further away is left alone: most prints are not government drafts at all, and asking
 * somebody to confirm that would bury the queue in questions with no answer.
 *
 * It runs from whichever side arrives second, and which that is cannot be assumed: RPL
 * comes first in the world, and second in this archive whenever the Sejm's register is
 * ingested before a backfill of RPL reaches the same draft.
 */
@Service
class DraftIdentityMatcher(
    private val drafts: DraftRepository,
    private val continuations: DraftContinuationRepository,
    private val candidates: DraftMatchCandidateRepository,
    private val properties: LegislativeProperties,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun joinDraftAcrossRegisters(recorded: DraftRecorded) {
        val identity = drafts.identityOf(recorded.draftId) ?: return
        // Neither register's key, or both of them: nothing to join, or joined already.
        val register = identity.register ?: return
        if (continuations.continuationOf(identity.id) != null) return

        val quoted = drafts.unjoinedDraftQuoting(identity.sharedNumbers, register.counterpart, identity.id)
        if (quoted != null) {
            join(identity.id, quoted, register, MatchMethod.EXACT, confidence = null)
            record("exact")
            log.debug("Joined draft {} to {} by a number both registers quote", identity.id, quoted)
            return
        }

        joinByTitle(identity, register)
    }

    private fun joinByTitle(identity: DraftIdentity, register: DraftRegister) {
        val counterpart = register.counterpart
        val closest = drafts.closestUnjoinedByTitle(
            normalisedTitle = identity.normalisedTitle,
            register = counterpart,
            atLeast = properties.reviewJoinAbove,
            // A government draft cannot have started after the print it became, and a
            // print cannot have started before the draft it came from. Which of the two
            // bounds applies is which register is being searched.
            startedNoLaterThan = identity.startedOn.takeIf { counterpart == DraftRegister.GOVERNMENT },
            startedNoEarlierThan = identity.startedOn.takeIf { counterpart == DraftRegister.SEJM },
            excluding = identity.id,
        )

        if (closest == null) {
            record("none")
            return
        }

        if (closest.similarity >= properties.automaticJoinAbove) {
            join(identity.id, closest.draftId, register, MatchMethod.FUZZY, closest.similarity)
            record("automatic")
            log.debug(
                "Joined draft {} to {} by title at {}",
                identity.id,
                closest.draftId,
                closest.similarity,
            )
            return
        }

        val (government, sejm) = pairOf(identity.id, closest.draftId, register)
        candidates.queueForReview(government, sejm, closest.similarity)
        record("review")
    }

    private fun join(
        draftId: DraftId,
        counterpartId: DraftId,
        register: DraftRegister,
        method: MatchMethod,
        confidence: Double?,
    ) {
        val (government, sejm) = pairOf(draftId, counterpartId, register)
        continuations.recordContinuation(government, sejm, method, confidence)
    }

    /**
     * The pair in the order the process runs in, whichever half of it was recorded.
     * The table's own columns say which is which, so getting this backwards would be a
     * draft that became its own predecessor.
     */
    private fun pairOf(draftId: DraftId, counterpartId: DraftId, register: DraftRegister): Pair<DraftId, DraftId> =
        when (register) {
            DraftRegister.GOVERNMENT -> draftId to counterpartId
            DraftRegister.SEJM -> counterpartId to draftId
        }

    private fun record(outcome: String) =
        meters.counter("legislative.draft_join.outcome", "outcome", outcome).increment()
}
