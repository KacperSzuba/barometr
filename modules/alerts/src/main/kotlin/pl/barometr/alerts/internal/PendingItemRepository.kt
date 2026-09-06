package pl.barometr.alerts.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import pl.barometr.shared.Ids
import pl.barometr.alerts.internal.jooq.tables.references.PENDING_ITEM
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The buffer between "something moved" and "somebody was told". SQL only.
 */
@Repository
class PendingItemRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * Records that something moved, unless the same thing is already waiting.
     *
     * A register restating an act it published last week produces the same row, and
     * the partial unique index is what collapses the two — one crawl of the Journal of
     * Laws restates thousands of acts, and a run that judged each of them separately
     * would do the work thousands of times to reach the same answer.
     */
    fun append(kind: String, subjectId: String): Boolean =
        dsl.insertInto(PENDING_ITEM)
            .set(PENDING_ITEM.ID, Ids.next())
            .set(PENDING_ITEM.KIND, kind)
            .set(PENDING_ITEM.SUBJECT_ID, subjectId)
            .set(PENDING_ITEM.RECORDED_AT, at(clock.instant()))
            .onConflict(PENDING_ITEM.KIND, PENDING_ITEM.SUBJECT_ID)
            .where(PENDING_ITEM.PROCESSED_AT.isNull)
            .doNothing()
            .execute() == 1

    /**
     * The oldest [limit] things nobody has judged yet, and that have been waiting since
     * at least [settledBefore].
     *
     * The cut-off is what keeps a judgement from racing the derivations it reads. An
     * act is buffered by a listener on the same event that has taxonomy classifying it
     * and legislative recording where it stands, all on one executor; an item judged
     * the instant it lands is judged against whichever of those finished first. It is
     * then marked judged, so nothing looks at it again — the failure is silent and
     * permanent, which is the kind this system is built to refuse.
     */
    fun waiting(limit: Int, settledBefore: Instant): List<PendingItem> =
        dsl.selectFrom(PENDING_ITEM)
            .where(PENDING_ITEM.PROCESSED_AT.isNull)
            .and(PENDING_ITEM.RECORDED_AT.le(at(settledBefore)))
            .orderBy(PENDING_ITEM.RECORDED_AT)
            .limit(limit)
            .fetch {
                PendingItem(it.id!!, it.kind!!, it.subjectId!!)
            }

    /**
     * Marks it judged. The row stays: it is the evidence that a run saw the thing at
     * all, which is half of any answer to "why was I not told".
     */
    fun markJudged(id: UUID) {
        dsl.update(PENDING_ITEM)
            .set(PENDING_ITEM.PROCESSED_AT, at(clock.instant()))
            .where(PENDING_ITEM.ID.eq(id))
            .execute()
    }

    private fun at(instant: Instant) = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
}
