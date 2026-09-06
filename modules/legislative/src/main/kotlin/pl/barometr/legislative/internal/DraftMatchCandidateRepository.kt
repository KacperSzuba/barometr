package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_MATCH_CANDIDATE
import pl.barometr.shared.Ids
import java.math.BigDecimal
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The queue of joins nobody is confident enough to make automatically. SQL only.
 */
@Repository
class DraftMatchCandidateRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * Queues a pair for review, unless the same pair is already waiting.
     *
     * `DO NOTHING` against the partial unique index on pending rows: matching runs
     * from an event, and events are redelivered, so without it a replay would fill the
     * queue with copies of one question.
     */
    fun queueForReview(governmentDraftId: DraftId, sejmDraftId: DraftId, confidence: Double) {
        dsl.insertInto(DRAFT_MATCH_CANDIDATE)
            .set(DRAFT_MATCH_CANDIDATE.ID, Ids.next())
            .set(DRAFT_MATCH_CANDIDATE.GOVERNMENT_DRAFT_ID, governmentDraftId.value)
            .set(DRAFT_MATCH_CANDIDATE.SEJM_DRAFT_ID, sejmDraftId.value)
            .set(DRAFT_MATCH_CANDIDATE.CONFIDENCE, BigDecimal.valueOf(confidence))
            .set(DRAFT_MATCH_CANDIDATE.STATUS, PENDING)
            .set(DRAFT_MATCH_CANDIDATE.CREATED_AT, now())
            .onConflictDoNothing()
            .execute()
    }

    /** Oldest first: a queue nobody works from the front of is not a queue. */
    fun awaitingReview(limit: Int): List<PendingDraftMatch> =
        pending()
            .orderBy(DRAFT_MATCH_CANDIDATE.CREATED_AT)
            .limit(limit)
            .fetch(::toPending)

    fun byId(id: UUID): PendingDraftMatch? =
        pending()
            .and(DRAFT_MATCH_CANDIDATE.ID.eq(id))
            .fetchOne(::toPending)

    /**
     * Every waiting pair with both titles beside it. The draft table is joined twice
     * under two aliases, because the two halves of a candidate are two rows of it.
     */
    private fun pending() = dsl.select(
        DRAFT_MATCH_CANDIDATE.ID,
        DRAFT_MATCH_CANDIDATE.GOVERNMENT_DRAFT_ID,
        DRAFT_MATCH_CANDIDATE.SEJM_DRAFT_ID,
        DRAFT_MATCH_CANDIDATE.CONFIDENCE,
        DRAFT_MATCH_CANDIDATE.CREATED_AT,
        GOVERNMENT.TITLE,
        SEJM.TITLE,
    )
        .from(DRAFT_MATCH_CANDIDATE)
        .join(GOVERNMENT).on(GOVERNMENT.ID.eq(DRAFT_MATCH_CANDIDATE.GOVERNMENT_DRAFT_ID))
        .join(SEJM).on(SEJM.ID.eq(DRAFT_MATCH_CANDIDATE.SEJM_DRAFT_ID))
        .where(DRAFT_MATCH_CANDIDATE.STATUS.eq(PENDING))

    /**
     * Records the reviewer's decision, and only while the row is still pending, so two
     * reviewers opening the same item cannot both decide it.
     */
    fun recordDecision(id: UUID, accepted: Boolean, reviewer: String): Boolean =
        dsl.update(DRAFT_MATCH_CANDIDATE)
            .set(DRAFT_MATCH_CANDIDATE.STATUS, if (accepted) ACCEPTED else REJECTED)
            .set(DRAFT_MATCH_CANDIDATE.REVIEWED_BY, reviewer)
            .set(DRAFT_MATCH_CANDIDATE.REVIEWED_AT, now())
            .where(DRAFT_MATCH_CANDIDATE.ID.eq(id))
            .and(DRAFT_MATCH_CANDIDATE.STATUS.eq(PENDING))
            .execute() == 1

    fun countAwaitingReview(): Int =
        dsl.fetchCount(DRAFT_MATCH_CANDIDATE, DRAFT_MATCH_CANDIDATE.STATUS.eq(PENDING))

    private fun toPending(record: Record) = PendingDraftMatch(
        id = record[DRAFT_MATCH_CANDIDATE.ID]!!,
        governmentDraftId = DraftId(record[DRAFT_MATCH_CANDIDATE.GOVERNMENT_DRAFT_ID]!!),
        governmentTitle = record[GOVERNMENT.TITLE]!!,
        sejmDraftId = DraftId(record[DRAFT_MATCH_CANDIDATE.SEJM_DRAFT_ID]!!),
        sejmTitle = record[SEJM.TITLE]!!,
        confidence = record[DRAFT_MATCH_CANDIDATE.CONFIDENCE]!!.toDouble(),
        createdAt = record[DRAFT_MATCH_CANDIDATE.CREATED_AT]!!.toInstant(),
    )

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private companion object {
        val GOVERNMENT = DRAFT.`as`("government_draft")
        val SEJM = DRAFT.`as`("sejm_draft")

        const val PENDING = "pending"
        const val ACCEPTED = "accepted"
        const val REJECTED = "rejected"
    }
}
