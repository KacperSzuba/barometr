package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.corpus.api.DocumentVersionId
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
}
