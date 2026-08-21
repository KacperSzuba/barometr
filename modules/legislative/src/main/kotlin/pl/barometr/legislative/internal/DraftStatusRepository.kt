package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_STATUS
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The read model: where every draft stands, without walking its history.
 *
 * Every column is derived, and the table can be dropped and rebuilt from
 * `stage_transition` at any time — which is exactly what [DraftStatusRebuild] does on
 * a schedule. It exists so that listing a thousand drafts does not mean reading a
 * thousand histories, and for nothing else: one draft's card is computed live, because
 * it can be.
 */
@Repository
class DraftStatusRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    @Transactional
    fun record(draftId: DraftId, status: DraftStatus) {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

        dsl.insertInto(DRAFT_STATUS)
            .set(DRAFT_STATUS.DRAFT_ID, draftId.value)
            .set(DRAFT_STATUS.CURRENT_STAGE, status.currentStage.wireName)
            .set(DRAFT_STATUS.ENTERED_AT, at(status.since))
            .set(DRAFT_STATUS.NEXT_STAGE, status.expectedNext?.wireName)
            .set(DRAFT_STATUS.NEXT_STAGE_ESTIMATED_AT, status.expectedNextBy?.let(::at))
            .set(DRAFT_STATUS.HARD_DEADLINE_AT, status.hardDeadline?.on?.let(::at))
            .set(DRAFT_STATUS.HARD_DEADLINE_KIND, status.hardDeadline?.kind?.wireName)
            .set(DRAFT_STATUS.STALLED_SINCE, status.stalledSince?.let(::at))
            .set(DRAFT_STATUS.UPDATED_AT, now)
            .onConflict(DRAFT_STATUS.DRAFT_ID)
            .doUpdate()
            .set(DRAFT_STATUS.CURRENT_STAGE, DSL.excluded(DRAFT_STATUS.CURRENT_STAGE))
            .set(DRAFT_STATUS.ENTERED_AT, DSL.excluded(DRAFT_STATUS.ENTERED_AT))
            .set(DRAFT_STATUS.NEXT_STAGE, DSL.excluded(DRAFT_STATUS.NEXT_STAGE))
            .set(DRAFT_STATUS.NEXT_STAGE_ESTIMATED_AT, DSL.excluded(DRAFT_STATUS.NEXT_STAGE_ESTIMATED_AT))
            .set(DRAFT_STATUS.HARD_DEADLINE_AT, DSL.excluded(DRAFT_STATUS.HARD_DEADLINE_AT))
            .set(DRAFT_STATUS.HARD_DEADLINE_KIND, DSL.excluded(DRAFT_STATUS.HARD_DEADLINE_KIND))
            .set(DRAFT_STATUS.STALLED_SINCE, DSL.excluded(DRAFT_STATUS.STALLED_SINCE))
            .set(DRAFT_STATUS.UPDATED_AT, now)
            .execute()
    }

    /** How many drafts sit at each stage, and how many of them have stopped moving. */
    @Transactional(readOnly = true)
    fun countByStage(): Map<LegislativeStage, Int> =
        dsl.select(DRAFT_STATUS.CURRENT_STAGE, DSL.count())
            .from(DRAFT_STATUS)
            .groupBy(DRAFT_STATUS.CURRENT_STAGE)
            .fetch()
            .mapNotNull { record -> record.value1()?.let(LegislativeStage::of)?.to(record.value2()) }
            .toMap()

    @Transactional(readOnly = true)
    fun countStalled(): Int = dsl.fetchCount(DRAFT_STATUS, DRAFT_STATUS.STALLED_SINCE.isNotNull)

    private fun at(instant: java.time.Instant) = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
}
