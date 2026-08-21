package pl.barometr.legislative.internal

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.barometr.legislative.api.DraftId
import java.util.UUID

/**
 * One draft, answered the way a reader asks: where is it, what happens next, when, and
 * how did it get here.
 *
 * Any authenticated caller may read it — this is the product's own data about a public
 * legislative process, and there is nothing here a signed-up user should not see. The
 * operator role guards the endpoints that spend somebody else's resources or decide
 * what a law *is*, which this does neither of.
 *
 * The response keeps an estimate and a fixed date in separate fields with separate
 * names, because the specification is emphatic about it and it is the one confusion
 * this product cannot afford: a reader who acts on a median believing it a deadline
 * has been misled by us, not by the Sejm.
 */
@RestController
@RequestMapping("/api/v1/legislative/drafts")
class DraftCardController(private val cards: DraftCards) {

    @GetMapping("/{id}")
    fun draft(@PathVariable id: UUID): DraftCardResponse {
        val card = cards.cardFor(DraftId(id))

        return DraftCardResponse(
            id = id,
            title = card.draft.title,
            initiator = card.draft.initiator.wireName,
            term = card.draft.term,
            startedOn = card.draft.startedOn?.toString(),
            closedOn = card.draft.closedOn?.toString(),
            outcome = card.draft.outcome?.wireName,
            currentStage = card.status?.let(::describeCurrent),
            expectedNext = card.status?.expectedNext?.let { next ->
                ExpectedNextResponse(
                    stage = next.wireName,
                    // An estimate, and the field says so twice: in its name and in the
                    // basis beside it.
                    estimatedAt = card.status.expectedNextBy?.toString(),
                    basis = MEDIAN_BASIS,
                )
            },
            hardDeadline = card.status?.hardDeadline?.let {
                HardDeadlineResponse(at = it.on.toString(), kind = it.kind.wireName)
            },
            stalledSince = card.status?.stalledSince?.toString(),
            history = card.history.map(::describe),
        )
    }

    private fun describeCurrent(status: DraftStatus) = StageResponse(
        stage = status.currentStage.wireName,
        since = status.since.toString(),
        until = null,
        sourceLabel = status.sourceLabel,
        isException = false,
    )

    private fun describe(stage: RecordedStage) = StageResponse(
        stage = stage.stage.wireName,
        since = stage.since.toString(),
        until = stage.until?.toString(),
        sourceLabel = stage.sourceLabel,
        isException = stage.isException,
    )

    data class DraftCardResponse(
        val id: UUID,
        val title: String,
        val initiator: String,
        val term: Int?,
        val startedOn: String?,
        val closedOn: String?,
        val outcome: String?,
        /** Null when nothing is recorded about where the draft has been. */
        val currentStage: StageResponse?,
        val expectedNext: ExpectedNextResponse?,
        val hardDeadline: HardDeadlineResponse?,
        val stalledSince: String?,
        val history: List<StageResponse>,
    )

    data class StageResponse(
        val stage: String,
        val since: String,
        val until: String?,
        /** The register's own word for it, which can be finer than the model's. */
        val sourceLabel: String?,
        val isException: Boolean,
    )

    /** A guess. Never rendered beside a fixed date without this shape around it. */
    data class ExpectedNextResponse(
        val stage: String,
        val estimatedAt: String?,
        val basis: String,
    )

    /** A date somebody else fixed, in a statute or a journal. */
    data class HardDeadlineResponse(val at: String, val kind: String)

    private companion object {
        const val MEDIAN_BASIS = "historical-median"
    }
}
