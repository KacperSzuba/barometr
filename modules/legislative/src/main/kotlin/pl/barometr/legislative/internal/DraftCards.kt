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
    private val continuations: DraftContinuationRepository,
    private val engine: DraftStatusEngine,
) {

    fun cardFor(draftId: DraftId): DraftCard {
        val draft = drafts.summaryOf(draftId) ?: throw UnknownDraftException(draftId.toString())
        val history = transitions.historyOf(draftId)

        return DraftCard(
            draft = draft,
            status = engine.statusOf(draft, history, paces.measure()),
            history = history,
            otherRegister = otherRegisterOf(draftId),
        )
    }

    /**
     * The joined draft, read but never merged into the one asked for.
     *
     * Two registers, two histories, and they are kept apart on purpose: the status
     * above is a judgement about *this* register's record, and folding six months of
     * government process into a print's timeline would change what "where is it now"
     * means without anybody asking for that. The reader gets both, labelled, and can
     * see the whole passage without being told a merged story.
     */
    private fun otherRegisterOf(draftId: DraftId): JoinedDraft? {
        val continuation = continuations.continuationOf(draftId) ?: return null
        val counterpartId = continuation.counterpartOf(draftId) ?: return null
        val counterpart = drafts.summaryOf(counterpartId) ?: return null

        return JoinedDraft(
            draft = counterpart,
            register = if (counterpartId == continuation.governmentDraftId) {
                DraftRegister.GOVERNMENT
            } else {
                DraftRegister.SEJM
            },
            joinedBy = continuation.joinedBy,
            confidence = continuation.confidence,
            history = transitions.historyOf(counterpartId),
        )
    }
}
