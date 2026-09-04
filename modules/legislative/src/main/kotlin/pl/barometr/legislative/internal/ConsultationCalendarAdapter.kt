package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.barometr.legislative.api.ConsultationCalendar
import pl.barometr.legislative.api.ConsultationDeadline
import pl.barometr.legislative.api.ConsultationId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.CONSULTATION
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import java.time.LocalDate

/**
 * The calendar's read side. A value type crosses the boundary; the row stays here.
 *
 * Only consultations a date was read for are answered with, which the queries get for
 * free from `closes_on`: a row waiting for its letter has none, `BETWEEN` does not
 * match a null, and so a consultation this system cannot date is one it says nothing
 * about rather than one it guesses at.
 *
 * The title is joined rather than copied onto the consultation. A ministry rewrites a
 * draft's title while it is out for comment, and a calendar entry naming the old one
 * would be this system quoting itself instead of the register.
 */
@Component
@Transactional(readOnly = true)
class ConsultationCalendarAdapter(private val dsl: DSLContext) : ConsultationCalendar {

    override fun closingBetween(from: LocalDate, until: LocalDate): List<ConsultationDeadline> =
        deadlines()
            .where(CONSULTATION.CLOSES_ON.between(from, until))
            // The day first, then the title, so two consultations closing together come
            // back in the same order every time a reader reloads the page.
            .orderBy(CONSULTATION.CLOSES_ON, DRAFT.TITLE)
            .fetch(::toDeadline)

    override fun consultationById(id: ConsultationId): ConsultationDeadline? =
        deadlines()
            .where(CONSULTATION.ID.eq(id.value))
            .and(CONSULTATION.CLOSES_ON.isNotNull)
            .fetchOne(::toDeadline)

    private fun deadlines() = dsl.select(
        CONSULTATION.ID,
        CONSULTATION.DRAFT_ID,
        DRAFT.TITLE,
        CONSULTATION.OPENED_ON,
        CONSULTATION.CLOSES_ON,
        CONSULTATION.DAYS_ALLOWED,
        CONSULTATION.SUBMISSION_ADDRESS,
        CONSULTATION.QUOTE,
    )
        .from(CONSULTATION)
        .join(DRAFT).on(DRAFT.ID.eq(CONSULTATION.DRAFT_ID))

    /**
     * The two `!!` on nullable columns are `ck_consultation_term_has_evidence` read
     * back: a row with a closing date has the words it was read from, and both queries
     * above ask only for rows with a closing date.
     */
    private fun toDeadline(record: Record) = ConsultationDeadline(
        id = ConsultationId(record[CONSULTATION.ID]!!),
        draftId = DraftId(record[CONSULTATION.DRAFT_ID]!!),
        draftTitle = record[DRAFT.TITLE]!!,
        opensOn = record[CONSULTATION.OPENED_ON],
        closesOn = record[CONSULTATION.CLOSES_ON]!!,
        daysAllowed = record[CONSULTATION.DAYS_ALLOWED],
        submissionAddress = record[CONSULTATION.SUBMISSION_ADDRESS],
        quote = record[CONSULTATION.QUOTE]!!,
    )
}
