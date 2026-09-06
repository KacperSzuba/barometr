package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.corpus.api.DocumentId
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.internal.jooq.tables.references.ACT
import pl.barometr.shared.Eli
import pl.barometr.shared.Ids
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Acts, and the title search identity resolution falls back on. SQL only.
 */
@Repository
class ActRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * The act with this ELI, created or refreshed from what the register now says.
     *
     * Upsert rather than insert-if-absent because ISAP restates an act every time
     * anything about it changes — a repeal date arrives years after publication — and
     * the row is a description of the act now, not a history of our readings of it.
     * The history is in the archive.
     */
    fun actFor(metadata: EliActMetadata, sourceDocumentId: DocumentId): ActId {
        val id = dsl.insertInto(ACT)
            .set(ACT.ID, Ids.next())
            .set(ACT.ELI, metadata.eli.value)
            .set(ACT.SOURCE_DOCUMENT_ID, sourceDocumentId.value)
            .set(ACT.TITLE, metadata.title)
            .set(ACT.TITLE_NORMALISED, ActTitles.normalise(metadata.title))
            .set(ACT.ACT_TYPE, metadata.type)
            .set(ACT.PUBLISHER, metadata.eli.publisher)
            .set(ACT.ANNOUNCED_ON, metadata.announcedOn)
            .set(ACT.IN_FORCE_FROM, metadata.inForceFrom)
            .set(ACT.CREATED_AT, now())
            .set(ACT.UPDATED_AT, now())
            // The index on `eli` is partial — an act exists here before it is
            // published and has one — and Postgres will only use a partial index to
            // arbitrate a conflict if the statement repeats its predicate.
            .onConflict(ACT.ELI)
            .where(ACT.ELI.isNotNull)
            .doUpdate()
            .set(ACT.TITLE, DSL.excluded(ACT.TITLE))
            .set(ACT.TITLE_NORMALISED, DSL.excluded(ACT.TITLE_NORMALISED))
            .set(ACT.ACT_TYPE, DSL.excluded(ACT.ACT_TYPE))
            .set(ACT.ANNOUNCED_ON, DSL.excluded(ACT.ANNOUNCED_ON))
            .set(ACT.IN_FORCE_FROM, DSL.excluded(ACT.IN_FORCE_FROM))
            // The document the act was last read from, which is the one whose newest
            // comparison answers "what changed": ISAP restates an act as a new document
            // only when it publishes a consolidated text.
            .set(ACT.SOURCE_DOCUMENT_ID, DSL.excluded(ACT.SOURCE_DOCUMENT_ID))
            .set(ACT.UPDATED_AT, now())
            .returningResult(ACT.ID)
            .fetchOne()

        return ActId(requireNotNull(id?.value1()) { "upsert of act ${metadata.eli} returned no id" })
    }

    /**
     * The archived document this act was last read from, or null for one nothing has
     * projected — an act created by identity matching from a print, before the journal
     * published it.
     */
    @Transactional(readOnly = true)
    fun sourceDocumentOf(id: ActId): DocumentId? =
        dsl.select(ACT.SOURCE_DOCUMENT_ID)
            .from(ACT)
            .where(ACT.ID.eq(id.value))
            .fetchOne()
            ?.value1()
            ?.let(::DocumentId)

    /**
     * The act whose title is closest to [normalisedTitle], if any is close enough.
     *
     * Two guards, and both matter more than the similarity number. Acts announced
     * before [notAnnouncedBefore] are excluded: a print cannot have become an act that
     * was published before the print existed, and without that rule the closest title
     * is often last year's version of the same law. And the floor is applied in the
     * database rather than after fetching, so a document with no plausible act at all
     * — every print for a bill still in committee — costs one query and returns
     * nothing, instead of arriving as a candidate nobody can decide.
     */
    fun closestByTitle(
        normalisedTitle: String,
        notAnnouncedBefore: LocalDate?,
        atLeast: Double,
    ): ActTitleMatch? {
        // `similarity()` is pg_trgm's, and the `%` operator is what lets the GIN index
        // narrow the search before it is computed. Neither has a jOOQ DSL equivalent,
        // so the operator is a template with bound values — never concatenated text.
        //
        // `%` applies Postgres's own `pg_trgm.similarity_threshold`, 0.3 by default,
        // which is why [atLeast] is documented as needing to stay above it: below, the
        // index would be the floor and this argument would silently stop mattering.
        val similarity = DSL.field(
            "similarity({0}, {1})",
            Double::class.java,
            ACT.TITLE_NORMALISED,
            DSL.value(normalisedTitle),
        )
        val indexable = DSL.condition("{0} % {1}", ACT.TITLE_NORMALISED, DSL.value(normalisedTitle))

        return dsl.select(ACT.ID, ACT.ELI, similarity)
            .from(ACT)
            .where(indexable)
            .and(similarity.ge(atLeast))
            .and(notAnnouncedBefore?.let { ACT.ANNOUNCED_ON.ge(it) } ?: DSL.noCondition())
            .orderBy(similarity.desc())
            .limit(1)
            .fetchOne { record ->
                ActTitleMatch(
                    actId = ActId(record.value1()!!),
                    eli = Eli(record.value2()!!),
                    similarity = record.value3()!!,
                )
            }
    }

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}
