package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import pl.barometr.corpus.api.DocumentId
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.internal.jooq.tables.references.ACT_MATCH_CANDIDATE
import pl.barometr.shared.Ids
import java.math.BigDecimal
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The queue of matches nobody is confident enough to make automatically. SQL only.
 */
@Repository
class ActMatchCandidateRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * Queues a document for review, unless it is already waiting.
     *
     * `DO NOTHING` against the partial unique index on pending rows: matching runs
     * from an event, and events are redelivered, so without it a replay would fill
     * the queue with copies of one decision.
     */
    fun queueForReview(
        documentId: DocumentId,
        actId: ActId?,
        scheme: IdentifierScheme,
        value: String,
        confidence: Double,
    ) {
        dsl.insertInto(ACT_MATCH_CANDIDATE)
            .set(ACT_MATCH_CANDIDATE.ID, Ids.next())
            .set(ACT_MATCH_CANDIDATE.DOCUMENT_ID, documentId.value)
            .set(ACT_MATCH_CANDIDATE.ACT_ID, actId?.value)
            .set(ACT_MATCH_CANDIDATE.SCHEME, scheme.wireName)
            .set(ACT_MATCH_CANDIDATE.VALUE, value)
            .set(ACT_MATCH_CANDIDATE.CONFIDENCE, BigDecimal.valueOf(confidence))
            .set(ACT_MATCH_CANDIDATE.STATUS, PENDING)
            .set(ACT_MATCH_CANDIDATE.CREATED_AT, now())
            .onConflictDoNothing()
            .execute()
    }

    /** Oldest first: a queue nobody works from the front of is not a queue. */
    fun awaitingReview(limit: Int): List<PendingActMatch> =
        dsl.selectFrom(ACT_MATCH_CANDIDATE)
            .where(ACT_MATCH_CANDIDATE.STATUS.eq(PENDING))
            .orderBy(ACT_MATCH_CANDIDATE.CREATED_AT)
            .limit(limit)
            .fetch(::toPending)

    fun byId(id: UUID): PendingActMatch? =
        dsl.selectFrom(ACT_MATCH_CANDIDATE)
            .where(ACT_MATCH_CANDIDATE.ID.eq(id))
            .and(ACT_MATCH_CANDIDATE.STATUS.eq(PENDING))
            .fetchOne(::toPending)

    /**
     * Records the reviewer's decision.
     *
     * Conditional on the row still being pending, so two reviewers opening the same
     * item cannot both decide it: the second update matches nothing and the caller is
     * told, rather than the first decision being quietly overwritten.
     */
    fun recordDecision(id: UUID, accepted: Boolean, reviewer: String): Boolean =
        dsl.update(ACT_MATCH_CANDIDATE)
            .set(ACT_MATCH_CANDIDATE.STATUS, if (accepted) ACCEPTED else REJECTED)
            .set(ACT_MATCH_CANDIDATE.REVIEWED_BY, reviewer)
            .set(ACT_MATCH_CANDIDATE.REVIEWED_AT, now())
            .where(ACT_MATCH_CANDIDATE.ID.eq(id))
            .and(ACT_MATCH_CANDIDATE.STATUS.eq(PENDING))
            .execute() == 1

    fun countAwaitingReview(): Int =
        dsl.fetchCount(ACT_MATCH_CANDIDATE, ACT_MATCH_CANDIDATE.STATUS.eq(PENDING))

    private fun toPending(record: org.jooq.Record) = PendingActMatch(
        id = record[ACT_MATCH_CANDIDATE.ID]!!,
        documentId = DocumentId(record[ACT_MATCH_CANDIDATE.DOCUMENT_ID]!!),
        actId = record[ACT_MATCH_CANDIDATE.ACT_ID]?.let(::ActId),
        scheme = IdentifierScheme.entries.first { it.wireName == record[ACT_MATCH_CANDIDATE.SCHEME] },
        value = record[ACT_MATCH_CANDIDATE.VALUE]!!,
        confidence = record[ACT_MATCH_CANDIDATE.CONFIDENCE]!!.toDouble(),
        createdAt = record[ACT_MATCH_CANDIDATE.CREATED_AT]!!.toInstant(),
    )

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private companion object {
        const val PENDING = "pending"
        const val ACCEPTED = "accepted"
        const val REJECTED = "rejected"
    }
}
