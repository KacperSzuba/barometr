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
    private val identifiers: DraftIdentifierRepository,
    private val filings: DraftFilingRepository,
    private val votes: VoteRepository,
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
            filings = filingsUnder(draftId),
            // One indexed join, run for every draft: unlike the filings above there is no
            // identifier to check first that would tell us not to bother — a Sejm print
            // is exactly the kind of draft that has votes, and a government draft that
            // has none costs an empty index lookup to establish it.
            votes = votes.votesCiting(draftId, VOTES_SHOWN),
        )
    }

    /**
     * What is filed under the draft in RPL, newest first.
     *
     * Two indexed lookups, and only for a draft RPL knows: a Sejm print has no project
     * id, so the second query is not run rather than run to find nothing.
     *
     * The other register's filings are deliberately not fetched along with a join. A
     * government draft and the print it became are kept apart everywhere else on this
     * card, and folding one's documents into the other's list would be the merged story
     * [otherRegisterOf] refuses to tell.
     */
    private fun filingsUnder(draftId: DraftId): List<DraftFiling> {
        val projectId = identifiers.identifierOf(draftId, DraftIdentifierScheme.RCL_PROJECT) ?: return emptyList()

        return filings.filingsOf(projectId, FILINGS_SHOWN)
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

    private companion object {
        /**
         * How many filings a card carries.
         *
         * A cap rather than a page, because a card is not a place to walk a list: RPL
         * files a handful of documents per stage across eight stages, so the ordinary
         * draft is well under this and the response is bounded whatever a ministry
         * files. A draft that reaches the cap loses its oldest filings from the card
         * and no other reading of it, which is the right thing to lose first.
         */
        const val FILINGS_SHOWN = 150

        /**
         * How many votes a card carries.
         *
         * A bill reaching a third reading is voted on a few times; one whose amendments
         * are each put separately reaches a few dozen, and a heavily contested one — the
         * budget — a few hundred. Capped where the card stops being readable rather than
         * where the query stops being cheap, and the newest are what survives the cap,
         * because "what happened to it most recently" is the question a card answers.
         */
        const val VOTES_SHOWN = 100
    }
}
