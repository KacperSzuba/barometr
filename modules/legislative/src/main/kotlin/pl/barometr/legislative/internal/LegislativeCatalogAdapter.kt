package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeCatalog
import pl.barometr.legislative.api.PublishedAct
import pl.barometr.legislative.api.TrackedDraft
import pl.barometr.legislative.internal.jooq.tables.references.ACT
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_IDENTIFIER
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_STATUS
import pl.barometr.shared.Eli
import java.util.UUID

/**
 * The context's read port. Everything crossing the boundary is a value type; a jOOQ
 * record leaving here would take the schema with it.
 *
 * Paged by identifier rather than by offset. The identifiers are time-ordered, so
 * `id > last` walks the whole table in order and cannot skip a row that another
 * transaction inserted while the walk was in progress — which an offset can, and does,
 * exactly while a rebuild is running alongside ingestion.
 */
@Component
@Transactional(readOnly = true)
class LegislativeCatalogAdapter(private val dsl: DSLContext) : LegislativeCatalog {

    override fun actById(id: ActId): PublishedAct? =
        acts().where(PUBLISHED).and(ACT.ID.eq(id.value)).fetchOne(::toAct)

    override fun actByEli(eli: Eli): PublishedAct? =
        acts().where(PUBLISHED).and(ACT.ELI.eq(eli.value)).fetchOne(::toAct)

    override fun actsAfter(after: ActId?, limit: Int): List<PublishedAct> =
        acts()
            .where(PUBLISHED)
            .and(after?.let { ACT.ID.gt(it.value) } ?: DSL.noCondition())
            .orderBy(ACT.ID)
            .limit(limit)
            .fetch(::toAct)

    override fun draftById(id: DraftId): TrackedDraft? =
        drafts().where(DRAFT.ID.eq(id.value)).fetchOne { toDraft(it, identifiersOf(id)) }

    override fun draftsAfter(after: DraftId?, limit: Int): List<TrackedDraft> {
        val page = drafts()
            .where(after?.let { DRAFT.ID.gt(it.value) } ?: DSL.noCondition())
            .orderBy(DRAFT.ID)
            .limit(limit)
            .fetch()

        // One query for the page's aliases rather than one per draft: a rebuild walks
        // every draft there is, and the per-row version of this is the query that makes
        // it take an afternoon.
        val identifiers = identifiersOf(page.map { it[DRAFT.ID]!! })

        return page.map { toDraft(it, identifiers[it[DRAFT.ID]].orEmpty()) }
    }

    private fun acts() = dsl.select(
        ACT.ID, ACT.ELI, ACT.TITLE, ACT.ACT_TYPE, ACT.PUBLISHER, ACT.ANNOUNCED_ON, ACT.IN_FORCE_FROM,
    ).from(ACT)

    private fun drafts() = dsl.select(
        DRAFT.ID, DRAFT.TITLE, DRAFT.INITIATOR, DRAFT.TERM, DRAFT.STARTED_ON, DRAFT.CLOSED_ON,
        DRAFT.OUTCOME, DRAFT_STATUS.CURRENT_STAGE,
    ).from(DRAFT).leftJoin(DRAFT_STATUS).on(DRAFT_STATUS.DRAFT_ID.eq(DRAFT.ID))

    private fun identifiersOf(id: DraftId): List<String> = identifiersOf(listOf(id.value))[id.value].orEmpty()

    private fun identifiersOf(ids: List<UUID>): Map<UUID, List<String>> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            dsl.select(DRAFT_IDENTIFIER.DRAFT_ID, DRAFT_IDENTIFIER.VALUE)
                .from(DRAFT_IDENTIFIER)
                .where(DRAFT_IDENTIFIER.DRAFT_ID.`in`(ids))
                .fetchGroups({ it.value1()!! }, { it.value2()!! })
        }

    /**
     * An act without an ELI is one this system inferred from a reference and has not
     * read yet: it has no title worth searching for, so it stays out of the index
     * until the register states it.
     */
    private val PUBLISHED = ACT.ELI.isNotNull

    private fun toAct(record: Record) = PublishedAct(
        id = ActId(record[ACT.ID]!!),
        eli = Eli(record[ACT.ELI]!!),
        title = record[ACT.TITLE]!!,
        type = record[ACT.ACT_TYPE]!!,
        publisher = record[ACT.PUBLISHER] ?: Eli(record[ACT.ELI]!!).publisher,
        announcedOn = record[ACT.ANNOUNCED_ON],
        inForceFrom = record[ACT.IN_FORCE_FROM],
    )

    private fun toDraft(record: Record, identifiers: List<String>) = TrackedDraft(
        id = DraftId(record[DRAFT.ID]!!),
        title = record[DRAFT.TITLE]!!,
        initiator = record[DRAFT.INITIATOR]!!,
        term = record[DRAFT.TERM],
        startedOn = record[DRAFT.STARTED_ON],
        closedOn = record[DRAFT.CLOSED_ON],
        outcome = record[DRAFT.OUTCOME],
        currentStage = record[DRAFT_STATUS.CURRENT_STAGE],
        identifiers = identifiers,
    )
}
