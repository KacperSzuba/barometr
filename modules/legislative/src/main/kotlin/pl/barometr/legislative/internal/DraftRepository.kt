package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.ACT
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.shared.Ids
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Drafts. SQL only.
 *
 * A draft has no natural key of its own — it is `UD383` in RPL and `term10/print/424`
 * in the Sejm, months apart — so finding one goes through [DraftIdentifierRepository]
 * and this holds only what a draft *is*, in the vocabulary of [DraftFromRegister]
 * that both registers translate into.
 */
@Repository
class DraftRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    fun insertDraft(draft: DraftFromRegister): DraftId {
        val id = Ids.next()

        dsl.insertInto(DRAFT)
            .set(DRAFT.ID, id)
            .set(DRAFT.TITLE, draft.title)
            .set(DRAFT.TITLE_NORMALISED, ActTitles.normalise(draft.title))
            .set(DRAFT.INITIATOR, draft.initiator.wireName)
            .set(DRAFT.TERM, draft.term)
            .set(DRAFT.STARTED_ON, draft.startedOn)
            .set(DRAFT.CLOSED_ON, draft.closedOn)
            .set(DRAFT.OUTCOME, draft.outcome?.wireName)
            .set(DRAFT.CREATED_AT, now())
            .set(DRAFT.UPDATED_AT, now())
            .execute()

        return DraftId(id)
    }

    /**
     * The register's current description of a draft it already knows.
     *
     * A restatement, not a history: how the draft got here is in `stage_transition`,
     * which is append-only precisely so this row can be overwritten without losing
     * anything.
     */
    fun restateDraft(id: DraftId, draft: DraftFromRegister) {
        dsl.update(DRAFT)
            .set(DRAFT.TITLE, draft.title)
            .set(DRAFT.TITLE_NORMALISED, ActTitles.normalise(draft.title))
            .set(DRAFT.INITIATOR, draft.initiator.wireName)
            .set(DRAFT.STARTED_ON, draft.startedOn)
            .set(DRAFT.CLOSED_ON, draft.closedOn)
            .set(DRAFT.OUTCOME, draft.outcome?.wireName)
            .set(DRAFT.UPDATED_AT, now())
            .where(DRAFT.ID.eq(id.value))
            .execute()
    }

    /** Set once the draft has been published and the act it became is known. */
    fun linkToAct(id: DraftId, actId: ActId) {
        dsl.update(DRAFT)
            .set(DRAFT.ACT_ID, actId.value)
            .set(DRAFT.UPDATED_AT, now())
            .where(DRAFT.ID.eq(id.value))
            .and(DRAFT.ACT_ID.isNull)
            .execute()
    }

    /**
     * A draft with the one hard date in the picture: the day the act it became starts
     * applying, joined from the act rather than copied onto the draft, so it cannot go
     * stale against the register that states it.
     */
    @Transactional(readOnly = true)
    fun summaryOf(id: DraftId): DraftSummary? =
        summaries().where(DRAFT.ID.eq(id.value)).fetchOne(::toSummary)

    /** Every draft, oldest first, for the read model to be rebuilt from. */
    @Transactional(readOnly = true)
    fun allSummaries(): List<DraftSummary> = summaries().orderBy(DRAFT.CREATED_AT).fetch(::toSummary)

    private fun summaries() = dsl.select(
        DRAFT.ID,
        DRAFT.TITLE,
        DRAFT.INITIATOR,
        DRAFT.TERM,
        DRAFT.STARTED_ON,
        DRAFT.CLOSED_ON,
        DRAFT.OUTCOME,
        ACT.IN_FORCE_FROM,
    )
        .from(DRAFT)
        .leftJoin(ACT).on(ACT.ID.eq(DRAFT.ACT_ID))

    private fun toSummary(record: Record) = DraftSummary(
        id = DraftId(record[DRAFT.ID]!!),
        title = record[DRAFT.TITLE]!!,
        initiator = DraftInitiator.entries.firstOrNull { it.wireName == record[DRAFT.INITIATOR] }
            ?: DraftInitiator.UNKNOWN,
        term = record[DRAFT.TERM],
        startedOn = record[DRAFT.STARTED_ON],
        closedOn = record[DRAFT.CLOSED_ON],
        outcome = DraftOutcome.entries.firstOrNull { it.wireName == record[DRAFT.OUTCOME] },
        inForceFrom = record[ACT.IN_FORCE_FROM],
    )

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}
