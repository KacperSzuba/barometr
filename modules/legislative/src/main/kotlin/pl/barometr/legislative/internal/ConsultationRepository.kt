package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.legislative.api.ConsultationId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.CATALOG_FOLDER
import pl.barometr.legislative.internal.jooq.tables.references.CONSULTATION
import pl.barometr.shared.Ids
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Consultations and the folders they are filed in. SQL only.
 *
 * The reading side of the calendar is [ConsultationCalendarAdapter], because what
 * crosses the context boundary is a value type and what is written here is a row.
 */
@Repository
class ConsultationRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * The consultation a draft's card opened at this stage, creating it if this is the
     * first time the card has been read.
     *
     * Idempotent through the unique index rather than a read-then-write, so two
     * deliveries of the same card cannot both create one. The row carries no dates: a
     * consultation this system knows about but has read no letter for is exactly what
     * the empty row means, and it is what a letter arriving months later is matched
     * against.
     */
    @Transactional
    fun openConsultation(draftId: DraftId, sourceCatalog: String): ConsultationId {
        val inserted = dsl.insertInto(CONSULTATION)
            .set(CONSULTATION.ID, Ids.next())
            .set(CONSULTATION.DRAFT_ID, draftId.value)
            .set(CONSULTATION.SOURCE_CATALOG, sourceCatalog)
            .set(CONSULTATION.KNOWN_AT, now())
            .onConflictDoNothing()
            .returningResult(CONSULTATION.ID)
            .fetchOne()
            ?.value1()

        return ConsultationId(inserted ?: existingConsultation(draftId, sourceCatalog))
    }

    /**
     * One folder of RPL's tree, as a page that lists it says so.
     *
     * `DO NOTHING` because the page is re-read every time anything beneath it changes,
     * and a folder does not move: the same edge restated is the normal case, and a
     * second parent claimed for a folder is a contradiction to leave alone rather than
     * a correction to apply.
     */
    fun recordFolder(catalogId: String, parentCatalogId: String) {
        dsl.insertInto(CATALOG_FOLDER)
            .set(CATALOG_FOLDER.CATALOG_ID, catalogId)
            .set(CATALOG_FOLDER.PARENT_CATALOG_ID, parentCatalogId)
            .set(CATALOG_FOLDER.KNOWN_AT, now())
            .onConflictDoNothing()
            .execute()
    }

    /**
     * The consultation a document filed in this folder belongs to, if any.
     *
     * Two ways to belong, in one query. A ministry that files its letter under the
     * stage itself is the first; the usual case is the second, where the letter is in
     * "Pisma kierujące projekt do konsultacji publicznych" and the consultation was
     * opened on the stage that folder sits in.
     *
     * One level up and no further. RPL nests a stage's folders directly under it and
     * files documents in those, so a second hop would answer a question the site does
     * not ask — and a chain of them would need a recursive query to be safe against a
     * cycle the tree has no way to produce.
     */
    @Transactional(readOnly = true)
    fun consultationInCatalog(catalogId: String): ConsultationId? =
        dsl.select(CONSULTATION.ID)
            .from(CONSULTATION)
            .where(
                CONSULTATION.SOURCE_CATALOG.eq(catalogId).or(
                    CONSULTATION.SOURCE_CATALOG.`in`(
                        DSL.select(CATALOG_FOLDER.PARENT_CATALOG_ID)
                            .from(CATALOG_FOLDER)
                            .where(CATALOG_FOLDER.CATALOG_ID.eq(catalogId)),
                    ),
                ),
            )
            .fetchOne { ConsultationId(it.value1()!!) }

    /**
     * Records what a letter states, and reports whether this consultation now believes
     * it.
     *
     * False means another document got here first and this one is not it correcting
     * itself — a dozen files are filed under one consultation stage and only the first
     * of them whose words set a term is believed. A later version of *that* document
     * is the exception the `WHERE` allows, because a ministry replacing its own letter
     * is a correction and the newer text is the one a reader would be shown.
     */
    @Transactional
    fun recordTerm(id: ConsultationId, fact: ConsultationFact): Boolean =
        dsl.update(CONSULTATION)
            .set(CONSULTATION.OPENED_ON, fact.opensOn)
            .set(CONSULTATION.CLOSES_ON, fact.closesOn)
            .set(CONSULTATION.DAYS_ALLOWED, fact.daysAllowed)
            .set(CONSULTATION.SUBMISSION_ADDRESS, fact.submissionAddress)
            .set(CONSULTATION.STATED_DOCUMENT, fact.statedIn.value)
            .set(CONSULTATION.STATED_BY, fact.statedBy.value)
            .set(CONSULTATION.CHAR_START, fact.charStart)
            .set(CONSULTATION.CHAR_END, fact.charEnd)
            .set(CONSULTATION.QUOTE, fact.quote)
            .set(CONSULTATION.KNOWN_AT, now())
            .where(CONSULTATION.ID.eq(id.value))
            .and(
                CONSULTATION.STATED_DOCUMENT.isNull
                    .or(CONSULTATION.STATED_DOCUMENT.eq(fact.statedIn.value)),
            )
            .execute() > 0

    private fun existingConsultation(draftId: DraftId, sourceCatalog: String) =
        dsl.select(CONSULTATION.ID)
            .from(CONSULTATION)
            .where(CONSULTATION.DRAFT_ID.eq(draftId.value))
            .and(CONSULTATION.SOURCE_CATALOG.eq(sourceCatalog))
            .fetchOne()!!
            .value1()!!

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}
