package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_IDENTIFIER
import pl.barometr.legislative.internal.jooq.tables.references.VOTE
import pl.barometr.legislative.internal.jooq.tables.references.VOTE_PRINT
import pl.barometr.shared.Ids
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The votes of the Sejm, and the prints each of them named. SQL only.
 *
 * A re-read of a sitting restates its votes, so the write is an upsert on the address
 * the source uses — term, sitting and number — rather than an insert guarded by a
 * lookup. The register does correct a tally, and the correction lands on the row it
 * corrects instead of beside it.
 */
@Repository
class VoteRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * Records a vote, or restates one already recorded, and answers with its identity.
     *
     * `returningResult` rather than a second query: the upsert already knows which row
     * it touched, and asking again would be a read that can disagree with the write it
     * follows.
     */
    fun recordVote(vote: SejmVoteRecord, readFrom: DocumentVersionId): VoteId {
        val id = dsl.insertInto(VOTE)
            .set(VOTE.ID, Ids.next())
            .set(VOTE.TERM, vote.term)
            .set(VOTE.SITTING, vote.sitting)
            .set(VOTE.VOTING_NUMBER, vote.votingNumber)
            .set(VOTE.SITTING_DAY, vote.sittingDay)
            .set(VOTE.TAKEN_AT, at(vote.takenAt))
            .set(VOTE.TITLE, vote.title)
            .set(VOTE.SUBJECT, vote.subject)
            .set(VOTE.METHOD, vote.method)
            .set(VOTE.MAJORITY, vote.majority)
            .set(VOTE.MAJORITY_VOTES, vote.majorityVotes)
            .set(VOTE.YES, vote.yes)
            .set(VOTE.NO, vote.no)
            .set(VOTE.ABSTAINED, vote.abstained)
            .set(VOTE.NOT_PARTICIPATING, vote.notParticipating)
            .set(VOTE.TOTAL_VOTED, vote.totalVoted)
            .set(VOTE.DOCUMENT_VERSION_ID, readFrom.value)
            .set(VOTE.KNOWN_AT, now())
            .onConflict(VOTE.TERM, VOTE.SITTING, VOTE.VOTING_NUMBER)
            .doUpdate()
            .set(VOTE.SITTING_DAY, DSL.excluded(VOTE.SITTING_DAY))
            .set(VOTE.TAKEN_AT, DSL.excluded(VOTE.TAKEN_AT))
            .set(VOTE.TITLE, DSL.excluded(VOTE.TITLE))
            .set(VOTE.SUBJECT, DSL.excluded(VOTE.SUBJECT))
            .set(VOTE.METHOD, DSL.excluded(VOTE.METHOD))
            .set(VOTE.MAJORITY, DSL.excluded(VOTE.MAJORITY))
            .set(VOTE.MAJORITY_VOTES, DSL.excluded(VOTE.MAJORITY_VOTES))
            .set(VOTE.YES, DSL.excluded(VOTE.YES))
            .set(VOTE.NO, DSL.excluded(VOTE.NO))
            .set(VOTE.ABSTAINED, DSL.excluded(VOTE.ABSTAINED))
            .set(VOTE.NOT_PARTICIPATING, DSL.excluded(VOTE.NOT_PARTICIPATING))
            .set(VOTE.TOTAL_VOTED, DSL.excluded(VOTE.TOTAL_VOTED))
            .set(VOTE.DOCUMENT_VERSION_ID, DSL.excluded(VOTE.DOCUMENT_VERSION_ID))
            .set(VOTE.KNOWN_AT, DSL.excluded(VOTE.KNOWN_AT))
            .returningResult(VOTE.ID)
            .fetchOne()
            ?.value1()

        return VoteId(requireNotNull(id) { "An upsert of vote ${vote.votingNumber} returned no identity" })
    }

    /**
     * The prints this vote's agenda item named.
     *
     * `DO NOTHING` on a repeat, and nothing is ever removed: a title is restated
     * identically on every re-read, and a citation the register later drops is still a
     * citation it once made.
     */
    fun citePrints(voteId: VoteId, addresses: List<String>) {
        if (addresses.isEmpty()) return

        // One statement for the list: a vote on six prints is one round trip, not six.
        dsl.insertInto(VOTE_PRINT, VOTE_PRINT.VOTE_ID, VOTE_PRINT.PRINT_ADDRESS)
            .apply { addresses.forEach { address -> values(voteId.value, address) } }
            .onConflictDoNothing()
            .execute()
    }

    /**
     * Every vote whose agenda item named a print of this draft, newest first.
     *
     * The join is on the print's address, which `draft_identifier` holds under
     * `druk_sejmowy` and this table holds as written: a draft the Sejm has not printed
     * matches nothing, which is the right answer for a draft that cannot have been voted
     * on.
     *
     * Capped rather than paged, like the filings beside it on the same card: a bill
     * carries a handful of votes and a heavily amended one a few dozen.
     */
    fun votesCiting(draftId: DraftId, limit: Int): List<RecordedVote> =
        dsl.select(
            VOTE.TERM,
            VOTE.SITTING,
            VOTE.VOTING_NUMBER,
            VOTE.TAKEN_AT,
            VOTE.TITLE,
            VOTE.SUBJECT,
            VOTE.METHOD,
            VOTE.MAJORITY,
            VOTE.YES,
            VOTE.NO,
            VOTE.ABSTAINED,
            VOTE.NOT_PARTICIPATING,
            VOTE.TOTAL_VOTED,
        )
            .from(VOTE)
            .join(VOTE_PRINT).on(VOTE_PRINT.VOTE_ID.eq(VOTE.ID))
            .join(DRAFT_IDENTIFIER).on(DRAFT_IDENTIFIER.VALUE.eq(VOTE_PRINT.PRINT_ADDRESS))
            .where(DRAFT_IDENTIFIER.DRAFT_ID.eq(draftId.value))
            .and(DRAFT_IDENTIFIER.SCHEME.eq(DraftIdentifierScheme.SEJM_PRINT.wireName))
            .orderBy(VOTE.TAKEN_AT.desc())
            .limit(limit)
            .fetch { record ->
                RecordedVote(
                    term = record[VOTE.TERM]!!,
                    sitting = record[VOTE.SITTING]!!,
                    votingNumber = record[VOTE.VOTING_NUMBER]!!,
                    takenAt = record[VOTE.TAKEN_AT]!!.toInstant(),
                    title = record[VOTE.TITLE]!!,
                    subject = record[VOTE.SUBJECT],
                    method = record[VOTE.METHOD]!!,
                    majority = record[VOTE.MAJORITY],
                    yes = record[VOTE.YES]!!,
                    no = record[VOTE.NO]!!,
                    abstained = record[VOTE.ABSTAINED]!!,
                    notParticipating = record[VOTE.NOT_PARTICIPATING]!!,
                    totalVoted = record[VOTE.TOTAL_VOTED]!!,
                )
            }

    private fun now(): OffsetDateTime = at(clock.instant())

    private fun at(moment: Instant): OffsetDateTime =
        OffsetDateTime.ofInstant(moment, ZoneOffset.UTC)
}
