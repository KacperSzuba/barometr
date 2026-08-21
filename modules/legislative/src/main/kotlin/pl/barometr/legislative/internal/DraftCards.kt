package pl.barometr.legislative.internal

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.legislative.api.DraftId

/**
 * Assembles one draft's card, computed rather than read from the read model.
 *
 * A card is one draft: its history is a handful of indexed rows, so computing it now
 * costs less than the staleness of a table rebuilt on a schedule would. `draft_status`
 * exists for the other shape of question — a thousand drafts at once — and both go
 * through the same [DraftStatusEngine], so the two can differ in freshness and never
 * in judgement.
 *
 * The medians are measured per request. That is an aggregate over the whole history,
 * which is cheap at a few thousand stages and will not be forever; when it stops being
 * cheap, the rebuild already computes them once an hour and this becomes a read of
 * what it stored.
 */
@Service
@Transactional(readOnly = true)
class DraftCards(
    private val drafts: DraftRepository,
    private val transitions: StageTransitionRepository,
    private val paces: StagePaceRepository,
    private val engine: DraftStatusEngine,
) {

    fun cardFor(draftId: DraftId): DraftCard {
        val draft = drafts.summaryOf(draftId) ?: throw UnknownDraftException(draftId.toString())
        val history = transitions.historyOf(draftId)

        return DraftCard(
            draft = draft,
            status = engine.statusOf(draft, history, paces.measure()),
            history = history,
        )
    }
}
