package pl.barometr.alerts.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import pl.barometr.alerts.internal.jooq.tables.references.CONSULTATION_FILING
import pl.barometr.identity.api.UserId
import pl.barometr.legislative.api.ConsultationId
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * "We have written in about this one." SQL only.
 */
@Repository
class ConsultationFilingRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * Records that this person has filed comments, or restates it.
     *
     * An upsert rather than an insert that can fail: pressing the button twice is a
     * person confirming what they already said, and the second press changes the note
     * and the day. What it must not do is come back as an error somebody has to read.
     */
    fun recordFiling(owner: UserId, consultation: ConsultationId, note: String?) {
        dsl.insertInto(CONSULTATION_FILING)
            .set(CONSULTATION_FILING.OWNER_ID, owner.value)
            .set(CONSULTATION_FILING.CONSULTATION_ID, consultation.value)
            .set(CONSULTATION_FILING.FILED_AT, now())
            .set(CONSULTATION_FILING.NOTE, note)
            .onConflict(CONSULTATION_FILING.OWNER_ID, CONSULTATION_FILING.CONSULTATION_ID)
            .doUpdate()
            .set(CONSULTATION_FILING.FILED_AT, now())
            .set(CONSULTATION_FILING.NOTE, note)
            .execute()
    }

    /** @return false when nothing was recorded, which is not an error either. */
    fun withdrawFiling(owner: UserId, consultation: ConsultationId): Boolean =
        dsl.deleteFrom(CONSULTATION_FILING)
            .where(CONSULTATION_FILING.OWNER_ID.eq(owner.value))
            .and(CONSULTATION_FILING.CONSULTATION_ID.eq(consultation.value))
            .execute() > 0

    /** Which of [consultations] this person has already answered. */
    fun filedAmong(owner: UserId, consultations: Collection<ConsultationId>): Set<ConsultationId> {
        if (consultations.isEmpty()) return emptySet()

        return dsl.select(CONSULTATION_FILING.CONSULTATION_ID)
            .from(CONSULTATION_FILING)
            .where(CONSULTATION_FILING.OWNER_ID.eq(owner.value))
            .and(CONSULTATION_FILING.CONSULTATION_ID.`in`(consultations.map { it.value }))
            .fetchSet { ConsultationId(it.value1()!!) }
    }

    fun filings(owner: UserId): List<RecordedFiling> =
        dsl.selectFrom(CONSULTATION_FILING)
            .where(CONSULTATION_FILING.OWNER_ID.eq(owner.value))
            .orderBy(CONSULTATION_FILING.FILED_AT.desc())
            .fetch {
                RecordedFiling(
                    consultation = ConsultationId(it.consultationId!!),
                    filedAt = it.filedAt!!.toInstant(),
                    note = it.note,
                )
            }

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}
