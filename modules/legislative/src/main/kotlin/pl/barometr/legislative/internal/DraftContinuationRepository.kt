package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_CONTINUATION
import java.math.BigDecimal
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Which government draft became which print. SQL only.
 */
@Repository
class DraftContinuationRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * Records the join, and says whether this call is the one that made it.
     *
     * `DO NOTHING` over both keys at once: the pair is unique in each direction, and a
     * redelivered event or the other register's projector arriving a second later must
     * find the join already made rather than fail. The boolean is what the caller
     * counts and logs — a join recorded twice is not news.
     */
    fun recordContinuation(
        governmentDraftId: DraftId,
        sejmDraftId: DraftId,
        method: MatchMethod,
        confidence: Double?,
    ): Boolean =
        dsl.insertInto(DRAFT_CONTINUATION)
            .set(DRAFT_CONTINUATION.GOVERNMENT_DRAFT_ID, governmentDraftId.value)
            .set(DRAFT_CONTINUATION.SEJM_DRAFT_ID, sejmDraftId.value)
            .set(DRAFT_CONTINUATION.JOINED_BY, method.wireName)
            .set(DRAFT_CONTINUATION.CONFIDENCE, confidence?.let(BigDecimal::valueOf))
            .set(DRAFT_CONTINUATION.JOINED_AT, now())
            .onConflictDoNothing()
            .execute() == 1

    /** The pair this draft belongs to, whichever half of it was asked for. */
    @Transactional(readOnly = true)
    fun continuationOf(draftId: DraftId): DraftContinuation? =
        dsl.selectFrom(DRAFT_CONTINUATION)
            .where(DRAFT_CONTINUATION.GOVERNMENT_DRAFT_ID.eq(draftId.value))
            .or(DRAFT_CONTINUATION.SEJM_DRAFT_ID.eq(draftId.value))
            .fetchOne(::toContinuation)

    fun countContinuations(): Int = dsl.fetchCount(DRAFT_CONTINUATION)

    private fun toContinuation(record: Record) = DraftContinuation(
        governmentDraftId = DraftId(record[DRAFT_CONTINUATION.GOVERNMENT_DRAFT_ID]!!),
        sejmDraftId = DraftId(record[DRAFT_CONTINUATION.SEJM_DRAFT_ID]!!),
        joinedBy = MatchMethod.entries.first { it.wireName == record[DRAFT_CONTINUATION.JOINED_BY] },
        confidence = record[DRAFT_CONTINUATION.CONFIDENCE]?.toDouble(),
        joinedAt = record[DRAFT_CONTINUATION.JOINED_AT]!!.toInstant(),
    )

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}
