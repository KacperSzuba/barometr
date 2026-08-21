package pl.barometr.legislative.internal

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The review queue: the matches this system will not make on its own.
 *
 * Operator-only for the same reason the backfill endpoints are. Accepting a match
 * rewrites what a user is told a law is, and registration is open — "authenticated"
 * here would mean "anyone who signed up".
 *
 * A decision is a POST to its own path rather than a status field on a body, because
 * the two decisions are different acts: accepting writes an identifier that outranks
 * every automatic match, rejecting writes nothing but the fact that a person looked.
 */
@RestController
@RequestMapping("/api/v1/legislative/act-matches")
@PreAuthorize("hasRole('OPERATOR')")
class ActMatchReviewController(
    private val candidates: ActMatchCandidateRepository,
    private val identifiers: ActIdentifierRepository,
) {

    @GetMapping
    fun awaitingReview(@RequestParam(defaultValue = "$DEFAULT_PAGE") limit: Int): List<PendingMatchResponse> =
        candidates.awaitingReview(limit.coerceIn(1, MAX_PAGE)).map(::describe)

    /**
     * Accepts the proposed act, and records who said so.
     *
     * The identifier is written as [MatchMethod.MANUAL] with no confidence: a person
     * decided, and dressing that up as a similarity score would suggest the machine
     * had something to do with it.
     */
    @PostMapping("/{id}/acceptance")
    fun accept(@PathVariable id: UUID, reviewer: Authentication): PendingMatchResponse {
        val match = candidates.byId(id) ?: throw UnknownActMatchException(id.toString())
        val actId = match.actId ?: throw UnknownActMatchException(id.toString())

        if (!candidates.recordDecision(id, accepted = true, reviewer = reviewer.name)) {
            throw UnknownActMatchException(id.toString())
        }
        identifiers.pointAtAct(match.scheme, match.value, actId, MatchMethod.MANUAL, confidence = null)

        return describe(match)
    }

    @PostMapping("/{id}/rejection")
    fun reject(@PathVariable id: UUID, reviewer: Authentication): PendingMatchResponse {
        val match = candidates.byId(id) ?: throw UnknownActMatchException(id.toString())

        if (!candidates.recordDecision(id, accepted = false, reviewer = reviewer.name)) {
            throw UnknownActMatchException(id.toString())
        }

        return describe(match)
    }

    private fun describe(match: PendingActMatch) = PendingMatchResponse(
        id = match.id,
        documentId = match.documentId.value,
        actId = match.actId?.value,
        scheme = match.scheme.wireName,
        value = match.value,
        confidence = match.confidence,
        queuedAt = match.createdAt.toString(),
    )

    data class PendingMatchResponse(
        val id: UUID,
        val documentId: UUID,
        /** Null when nothing was close enough to propose, which a reviewer may still fix. */
        val actId: UUID?,
        val scheme: String,
        val value: String,
        val confidence: Double,
        val queuedAt: String,
    )

    private companion object {
        const val DEFAULT_PAGE = 50
        const val MAX_PAGE = 200
    }
}
