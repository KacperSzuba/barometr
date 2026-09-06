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
 * The queue of joins between the two registers that this system will not make on its
 * own.
 *
 * Operator-only for the same reason [ActMatchReviewController] is: accepting one
 * rewrites what a reader is told about a draft — which consultation was its, which
 * ministry filed it — and registration is open, so "authenticated" here would mean
 * "anyone who signed up".
 *
 * A decision is a POST to its own path rather than a status field on a body, because
 * the two decisions are different acts: accepting joins two drafts into one case,
 * rejecting writes nothing but the fact that a person looked.
 */
@RestController
@RequestMapping("/api/v1/legislative/draft-matches")
@PreAuthorize("hasRole('OPERATOR')")
class DraftMatchReviewController(private val review: DraftMatchReview) {

    @GetMapping
    fun awaitingReview(@RequestParam(defaultValue = "$DEFAULT_PAGE") limit: Int): List<PendingJoinResponse> =
        review.awaitingReview(limit.coerceIn(1, MAX_PAGE)).map(::describe)

    @PostMapping("/{id}/acceptance")
    fun accept(@PathVariable id: UUID, reviewer: Authentication): PendingJoinResponse =
        describe(review.acceptMatch(id, reviewer.name))

    @PostMapping("/{id}/rejection")
    fun reject(@PathVariable id: UUID, reviewer: Authentication): PendingJoinResponse =
        describe(review.rejectMatch(id, reviewer.name))

    private fun describe(match: PendingDraftMatch) = PendingJoinResponse(
        id = match.id,
        governmentDraftId = match.governmentDraftId.value,
        governmentTitle = match.governmentTitle,
        sejmDraftId = match.sejmDraftId.value,
        sejmTitle = match.sejmTitle,
        confidence = match.confidence,
        queuedAt = match.createdAt.toString(),
    )

    /** Both titles, because they are what the reviewer is actually comparing. */
    data class PendingJoinResponse(
        val id: UUID,
        val governmentDraftId: UUID,
        val governmentTitle: String,
        val sejmDraftId: UUID,
        val sejmTitle: String,
        val confidence: Double,
        val queuedAt: String,
    )

    private companion object {
        const val DEFAULT_PAGE = 50
        const val MAX_PAGE = 200
    }
}
