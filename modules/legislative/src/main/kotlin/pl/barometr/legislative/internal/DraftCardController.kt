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
            // The same draft in the other register, under the name that says which
            // direction the reader is travelling. Both fields are never filled at
            // once: a draft has one predecessor or one continuation, not both.
            precededBy = card.otherRegister?.takeIf { it.register == DraftRegister.GOVERNMENT }?.let(::describeJoined),
            continuedAs = card.otherRegister?.takeIf { it.register == DraftRegister.SEJM }?.let(::describeJoined),
            filings = card.filings.map(::describe),
            votes = card.votes.map(::describe),
        )
    }

    /**
     * One vote, with the numbers and the rule they were counted under — and no verdict.
     *
     * There is no `passed` field, and its absence is deliberate: the Sejm's record does
     * not state one, and a boolean computed here from the majority and the tally would
     * be this system's arithmetic reaching a client as the Sejm's word. A reader is shown
     * what was counted and how many votes the rule required.
     */
    private fun describe(vote: RecordedVote) = VoteResponse(
        term = vote.term,
        sitting = vote.sitting,
        votingNumber = vote.votingNumber,
        takenAt = vote.takenAt.toString(),
        title = vote.title,
        subject = vote.subject,
        method = vote.method,
        majority = vote.majority,
        yes = vote.yes,
        no = vote.no,
        abstained = vote.abstained,
        notParticipating = vote.notParticipating,
        totalVoted = vote.totalVoted,
    )

    /**
     * One filed document, with the id that reaches it.
     *
     * `documentId` is what a client follows to `/api/v1/corpus/documents/{id}/changes`,
     * and it is null for a file RPL lists that this system does not hold. Reported
     * anyway, because "the ministry filed an impact assessment and we have not got it"
     * is a truer answer than a shorter list.
     */
    private fun describe(filing: DraftFiling) = FilingResponse(
        documentId = filing.documentId?.value,
        fileName = filing.fileName,
        folder = filing.catalogId,
        author = filing.author,
        filedOn = filing.filedOn?.toString(),
    )

    private fun describeJoined(joined: JoinedDraft) = JoinedDraftResponse(
        id = joined.draft.id.value,
        title = joined.draft.title,
        startedOn = joined.draft.startedOn?.toString(),
        closedOn = joined.draft.closedOn?.toString(),
        // How the two were tied together, because a join by title is a claim and a
        // number both registers print is a fact, and a reader acting on a consultation
        // is entitled to know which of the two they have.
        joinedBy = joined.joinedBy.wireName,
        confidence = joined.confidence,
        history = joined.history.map(::describe),
    )

    private fun describeCurrent(status: DraftStatus) = StageResponse(
        stage = status.currentStage.wireName,
        since = status.since.toString(),
        // Filled only for a draft that has left the register describing it here — a
        // government draft whose print the Sejm has since taken. `continuedAs` beside
        // it says where the reader should look next.
        until = status.until?.toString(),
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
        /** The government's draft this print came from, once the two have been joined. */
        val precededBy: JoinedDraftResponse?,
        /** The print this government draft became, once the two have been joined. */
        val continuedAs: JoinedDraftResponse?,
        /** What the ministry filed, newest first. Empty for a Sejm print. */
        val filings: List<FilingResponse>,
        /** How the Sejm voted on it, newest first. Empty until it has been voted on. */
        val votes: List<VoteResponse>,
    )

    /** One voting of the Sejm whose agenda item named a print of this draft. */
    data class VoteResponse(
        val term: Int,
        /** The sitting and the number within it, which is how the Sejm cites a vote. */
        val sitting: Int,
        val votingNumber: Int,
        val takenAt: String,
        /** The agenda item, in the register's words. */
        val title: String,
        /** What this vote decided, where the register says it separately from the item. */
        val subject: String?,
        /** `ELECTRONIC`, `ON_LIST`, `TRADITIONAL` — the register's own word. */
        val method: String,
        /** The rule the vote was held under, as stated; null where it was not. */
        val majority: String?,
        val yes: Int,
        val no: Int,
        val abstained: Int,
        val notParticipating: Int,
        val totalVoted: Int,
    )

    /** A document filed under the draft in RPL. */
    data class FilingResponse(
        /** Corpus's id for the file; null for one RPL lists and the archive does not hold. */
        val documentId: UUID?,
        val fileName: String?,
        /** RPL's id for the folder it sits in, which is how one filing is grouped with another. */
        val folder: String,
        val author: String?,
        val filedOn: String?,
    )

    /** The other register's record of the same draft, with its own timeline. */
    data class JoinedDraftResponse(
        val id: UUID,
        val title: String,
        val startedOn: String?,
        val closedOn: String?,
        val joinedBy: String,
        /** The title similarity that carried the join; absent when a number or a person did. */
        val confidence: Double?,
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
