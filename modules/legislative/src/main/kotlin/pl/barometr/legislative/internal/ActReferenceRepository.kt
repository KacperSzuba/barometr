package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.jooq.TableField
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.internal.jooq.tables.records.ActReferenceRecord
import pl.barometr.legislative.internal.jooq.tables.references.ACT
import pl.barometr.legislative.internal.jooq.tables.references.ACT_REFERENCE
import pl.barometr.shared.Eli
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The change graph. SQL only.
 */
@Repository
class ActReferenceRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * Replaces every edge this act states, in one transaction.
     *
     * Replace rather than merge, because the register corrects itself: a reference
     * listed in error is simply absent from the next reading, and an edge that could
     * only ever be added would leave the correction invisible. Edges pointing *at*
     * this act are untouched — they belong to the acts that state them.
     */
    @Transactional
    fun replaceReferencesFrom(
        act: Eli,
        edges: List<ActReferenceEdge>,
        statedBy: DocumentVersionId,
    ) {
        dsl.deleteFrom(ACT_REFERENCE)
            .where(ACT_REFERENCE.FROM_ELI.eq(act.value))
            .execute()

        if (edges.isEmpty()) return

        val recordedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        dsl.batch(
            edges.map { edge ->
                dsl.insertInto(ACT_REFERENCE)
                    .set(ACT_REFERENCE.FROM_ELI, edge.from.value)
                    .set(ACT_REFERENCE.TO_ELI, edge.to.value)
                    .set(ACT_REFERENCE.RELATION, edge.relation.wireName)
                    .set(ACT_REFERENCE.SOURCE_DOCUMENT_VERSION_ID, statedBy.value)
                    .set(ACT_REFERENCE.RECORDED_AT, recordedAt)
                    // An edge into this act may already have been stated by the act at
                    // its other end. Same fact, one row.
                    .onConflictDoNothing()
            },
        ).execute()
    }

    /**
     * What this act does to others.
     *
     * The other act's title comes from a left join, because the register cites acts
     * this archive may not hold — a statute from 1997 amended by one from 2026 is named
     * in the newer act's references whether or not anybody ever ingested it. A citation
     * without a title is still worth showing: the address is what a reader recognises,
     * and hiding it would make the graph look smaller than it is.
     */
    fun changesMadeBy(act: Eli): List<ActCitation> =
        citations(anchor = ACT_REFERENCE.FROM_ELI, other = ACT_REFERENCE.TO_ELI, act = act)

    /** What others do to this act — the direction that answers "is this still current". */
    fun changesMadeTo(act: Eli): List<ActCitation> =
        citations(anchor = ACT_REFERENCE.TO_ELI, other = ACT_REFERENCE.FROM_ELI, act = act)

    private fun citations(
        anchor: TableField<ActReferenceRecord, String?>,
        other: TableField<ActReferenceRecord, String?>,
        act: Eli,
    ): List<ActCitation> {
        val cited = ACT.`as`("cited")

        return dsl.select(other, ACT_REFERENCE.RELATION, cited.ID, cited.TITLE, cited.ANNOUNCED_ON)
            .from(ACT_REFERENCE)
            .leftJoin(cited).on(cited.ELI.eq(other))
            .where(anchor.eq(act.value))
            // Newest first, and the ones this archive does not hold last: a reader
            // scanning what changed an act wants this year's amendment at the top.
            .orderBy(cited.ANNOUNCED_ON.desc().nullsLast())
            .fetch { row ->
                ActCitation(
                    eli = Eli(row[other]!!),
                    relation = ActRelation.of(row[ACT_REFERENCE.RELATION]!!)
                        ?: error("stored relation '${row[ACT_REFERENCE.RELATION]}'"),
                    act = row[cited.ID]?.let(::ActId),
                    title = row[cited.TITLE],
                    announcedOn = row[cited.ANNOUNCED_ON],
                )
            }
    }
}
