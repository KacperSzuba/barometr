package pl.barometr.legislative.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * What a reviewer's decision actually does.
 *
 * Its own service because accepting is two writes that must happen together: the
 * candidate leaves the queue and an identifier starts pointing at the act. Split
 * across a controller they would be two requests' worth of state with a gap in the
 * middle, and the gap is a document pinned to an act nobody agreed to.
 */
@Service
class ActMatchReview(
    private val candidates: ActMatchCandidateRepository,
    private val identifiers: ActIdentifierRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun awaitingReview(limit: Int): List<PendingActMatch> = candidates.awaitingReview(limit)

    /**
     * Accepts the proposed act and records who said so.
     *
     * The identifier is written as [MatchMethod.MANUAL] with no confidence: a person
     * decided, and dressing that up as a similarity would suggest the machine had
     * something to do with it. It outranks the automatic link it replaces, which is
     * the point of a queue.
     */
    @Transactional
    fun acceptMatch(id: UUID, reviewer: String): PendingActMatch {
        val match = decide(id, accepted = true, reviewer = reviewer)
        val actId = match.actId ?: throw UnknownActMatchException(id.toString())

        identifiers.pointAtAct(match.scheme, match.value, actId, MatchMethod.MANUAL, confidence = null)
        log.info("{} pinned {} to act {} by hand", reviewer, match.value, actId)

        return match
    }

    @Transactional
    fun rejectMatch(id: UUID, reviewer: String): PendingActMatch =
        decide(id, accepted = false, reviewer = reviewer)

    /**
     * Reads and decides in one step, and only if the row is still waiting. Two
     * reviewers opening the same item cannot both decide it: the second finds nothing
     * to decide rather than overwriting the first.
     */
    private fun decide(id: UUID, accepted: Boolean, reviewer: String): PendingActMatch {
        val match = candidates.byId(id) ?: throw UnknownActMatchException(id.toString())
        if (!candidates.recordDecision(id, accepted, reviewer)) throw UnknownActMatchException(id.toString())

        return match
    }
}
