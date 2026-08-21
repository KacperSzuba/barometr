package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
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
 * and this holds only what a draft *is*.
 */
@Repository
class DraftRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    fun insertDraft(record: SejmProcessRecord): DraftId {
        val id = Ids.next()

        dsl.insertInto(DRAFT)
            .set(DRAFT.ID, id)
            .set(DRAFT.TITLE, record.title)
            .set(DRAFT.TITLE_NORMALISED, ActTitles.normalise(record.title))
            .set(DRAFT.INITIATOR, record.initiator.wireName)
            .set(DRAFT.TERM, record.term)
            .set(DRAFT.CLOSED_ON, record.closedOn)
            .set(DRAFT.OUTCOME, record.outcome?.wireName)
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
    fun restateDraft(id: DraftId, record: SejmProcessRecord) {
        dsl.update(DRAFT)
            .set(DRAFT.TITLE, record.title)
            .set(DRAFT.TITLE_NORMALISED, ActTitles.normalise(record.title))
            .set(DRAFT.INITIATOR, record.initiator.wireName)
            .set(DRAFT.CLOSED_ON, record.closedOn)
            .set(DRAFT.OUTCOME, record.outcome?.wireName)
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

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}
