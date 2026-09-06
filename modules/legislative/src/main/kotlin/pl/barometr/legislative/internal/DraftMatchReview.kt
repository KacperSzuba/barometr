package pl.barometr.legislative.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * What a reviewer's decision about a join actually does.
 *
 * Its own service for the same reason [ActMatchReview] is: accepting is two writes
 * that must happen together — the candidate leaves the queue and the two drafts become
 * one case. Split across a controller they would be two requests' worth of state with
 * a gap in the middle, and the gap is a reader shown a consultation nobody agreed
 * belongs to the print they are reading.
 */
@Service
class DraftMatchReview(
    private val candidates: DraftMatchCandidateRepository,
    private val continuations: DraftContinuationRepository,
    private val governmentProcess: GovernmentProcessClosure,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun awaitingReview(limit: Int): List<PendingDraftMatch> = candidates.awaitingReview(limit)

    /**
     * Joins the pair and records who said so.
     *
     * Written as [MatchMethod.MANUAL] with no confidence: a person decided, and
     * dressing that up as a similarity would suggest the machine had something to do
     * with it.
     *
     * Three things can be true when the decision lands, and they are not the same
     * answer. Nothing is joined yet, so this join is made. The very pair in front of
     * the reviewer is already joined — the matcher reached the same conclusion while
     * the item waited — and agreeing with it is not a failure. Or one of the two drafts
     * belongs to a *different* join, and that has to be undone before this one can be
     * made, so the decision is refused and rolled back with it.
     */
    @Transactional
    fun acceptMatch(id: UUID, reviewer: String): PendingDraftMatch {
        val match = decide(id, accepted = true, reviewer = reviewer)
        val standing = continuations.continuationOf(match.governmentDraftId)
            ?: continuations.continuationOf(match.sejmDraftId)

        when {
            standing == null -> {
                continuations.recordContinuation(
                    match.governmentDraftId,
                    match.sejmDraftId,
                    MatchMethod.MANUAL,
                    confidence = null,
                )
                log.info(
                    "{} joined government draft {} to print {} by hand",
                    reviewer,
                    match.governmentDraftId,
                    match.sejmDraftId,
                )
            }

            standing.governmentDraftId == match.governmentDraftId &&
                standing.sejmDraftId == match.sejmDraftId ->
                log.info("{} confirmed the join of {} and {}", reviewer, match.governmentDraftId, match.sejmDraftId)

            else -> throw DraftAlreadyJoinedException(match.governmentDraftId.toString())
        }

        // Whichever of the two branches got here, the pair now stands, and the same
        // thing follows from it as from a join the matcher made on its own: the
        // government's process ended when the Sejm printed the draft.
        governmentProcess.closeOnArrivalInSejm(match.governmentDraftId, match.sejmDraftId)

        return match
    }

    @Transactional
    fun rejectMatch(id: UUID, reviewer: String): PendingDraftMatch =
        decide(id, accepted = false, reviewer = reviewer)

    /**
     * Reads and decides in one step, and only if the row is still waiting: two
     * reviewers opening the same item cannot both decide it — the second finds nothing
     * to decide rather than overwriting the first.
     */
    private fun decide(id: UUID, accepted: Boolean, reviewer: String): PendingDraftMatch {
        val match = candidates.byId(id) ?: throw UnknownDraftMatchException(id.toString())
        if (!candidates.recordDecision(id, accepted, reviewer)) throw UnknownDraftMatchException(id.toString())

        return match
    }
}
