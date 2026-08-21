package pl.barometr.corpus.internal

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import pl.barometr.corpus.api.ArchivedDocument
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.corpus.internal.jooq.tables.references.DOCUMENT
import pl.barometr.corpus.internal.jooq.tables.references.DOCUMENT_VERSION
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.shared.ContentHash
import pl.barometr.shared.Ids
import pl.barometr.sources.api.SourceId
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Documents and their versions. SQL only — no blob reads, no events, no policy.
 */
@Repository
class DocumentRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * The document this address belongs to, creating it the first time it is seen.
     *
     * `DO UPDATE` rather than `DO NOTHING` for two reasons. It returns the id in
     * every case, which `DO NOTHING` does not, so there is no second query and no
     * read-then-write gap for two runs to fall into. And a document that acquires a
     * title later — a source that published it without one, a reader taught to find
     * it — gets it, while a source that has stopped sending one does not erase what
     * we already knew.
     */
    fun documentFor(
        sourceId: SourceId,
        externalId: ExternalId,
        kind: DocumentKind,
        title: String?,
    ): DocumentId {
        val id = dsl.insertInto(DOCUMENT)
            .set(DOCUMENT.ID, Ids.next())
            .set(DOCUMENT.SOURCE_ID, sourceId.value)
            .set(DOCUMENT.EXTERNAL_ID, externalId.value)
            .set(DOCUMENT.KIND, kind.value)
            .set(DOCUMENT.TITLE, title)
            .set(DOCUMENT.CREATED_AT, now())
            .onConflict(DOCUMENT.SOURCE_ID, DOCUMENT.EXTERNAL_ID)
            .doUpdate()
            .set(DOCUMENT.TITLE, DSL.coalesce(DSL.excluded(DOCUMENT.TITLE), DOCUMENT.TITLE))
            .returningResult(DOCUMENT.ID)
            .fetchOne()

        return DocumentId(requireNotNull(id?.value1()) { "upsert of document '$externalId' returned no id" })
    }

    /**
     * Appends a version, or reports that this exact content is already the archive's.
     *
     * Numbering and chaining happen inside the statement rather than in a read
     * followed by a write: `version_no` is computed from the rows present at insert
     * time and `previous_version_id` from the same set, so two runs racing on one
     * document cannot both read "the latest is 3" and both write a 4.
     *
     * The unique index on `(document_id, content_hash)` is what makes a replay free —
     * re-reading a source that changed nothing appends nothing. The other unique
     * index, on `(document_id, version_no)`, is what makes the race safe: the loser is
     * rejected outright rather than quietly chained onto the same predecessor, and the
     * event register redelivers it against the row the winner left behind.
     *
     * @return where the version landed, or null when the content was already held.
     */
    fun appendVersionIfNew(
        documentId: DocumentId,
        rawDocumentId: UUID,
        contentHash: ContentHash,
        publishedAt: Instant?,
    ): RecordedVersion? {
        val ofDocument = DOCUMENT_VERSION.DOCUMENT_ID.eq(documentId.value)

        val nextNumber = DSL.select(DSL.coalesce(DSL.max(DOCUMENT_VERSION.VERSION_NO), 0).plus(1))
            .from(DOCUMENT_VERSION)
            .where(ofDocument)

        val latestSoFar = DSL.select(DOCUMENT_VERSION.ID)
            .from(DOCUMENT_VERSION)
            .where(ofDocument)
            .orderBy(DOCUMENT_VERSION.VERSION_NO.desc())
            .limit(1)

        val recorded = dsl.insertInto(DOCUMENT_VERSION)
            .set(DOCUMENT_VERSION.ID, Ids.next())
            .set(DOCUMENT_VERSION.DOCUMENT_ID, documentId.value)
            .set(DOCUMENT_VERSION.VERSION_NO, DSL.field(nextNumber))
            .set(DOCUMENT_VERSION.PREVIOUS_VERSION_ID, DSL.field(latestSoFar))
            .set(DOCUMENT_VERSION.RAW_DOCUMENT_ID, rawDocumentId)
            .set(DOCUMENT_VERSION.CONTENT_HASH, contentHash.bytes)
            .set(DOCUMENT_VERSION.PUBLISHED_AT, publishedAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) })
            .set(DOCUMENT_VERSION.CREATED_AT, now())
            .onConflict(DOCUMENT_VERSION.DOCUMENT_ID, DOCUMENT_VERSION.CONTENT_HASH)
            .doNothing()
            .returningResult(DOCUMENT_VERSION.ID, DOCUMENT_VERSION.VERSION_NO)
            .fetchOne()
            ?: return null

        return RecordedVersion(
            id = DocumentVersionId(recorded.value1()!!),
            versionNo = recorded.value2()!!,
        )
    }

    /**
     * A document as another context sees it, with the day its newest version was
     * issued.
     *
     * The date belongs to the version rather than to the document — a draft's page
     * carries a new one every time it is republished — so it is read from the latest
     * version rather than copied onto the document, where it would immediately start
     * disagreeing with the versions underneath it.
     */
    fun byId(id: DocumentId): ArchivedDocument? {
        val publishedAt = DSL.select(DOCUMENT_VERSION.PUBLISHED_AT)
            .from(DOCUMENT_VERSION)
            .where(DOCUMENT_VERSION.DOCUMENT_ID.eq(DOCUMENT.ID))
            .orderBy(DOCUMENT_VERSION.VERSION_NO.desc())
            .limit(1)
            .asField<OffsetDateTime?>()

        return dsl.select(DOCUMENT.EXTERNAL_ID, DOCUMENT.KIND, DOCUMENT.TITLE, publishedAt)
            .from(DOCUMENT)
            .where(DOCUMENT.ID.eq(id.value))
            .fetchOne { record ->
                ArchivedDocument(
                    id = id,
                    externalId = ExternalId(record.value1()!!),
                    kind = DocumentKind(record.value2()!!),
                    title = record.value3(),
                    publishedAt = record.value4()?.toInstant(),
                )
            }
    }

    /**
     * Counted in the database rather than by loading rows: this feeds a gauge, and a
     * gauge that walks the corpus on every scrape is a gauge nobody can afford to
     * keep.
     */
    fun countByKind(): Map<DocumentKind, Int> =
        dsl.select(DOCUMENT.KIND, DSL.count())
            .from(DOCUMENT)
            .groupBy(DOCUMENT.KIND)
            .fetch()
            .associate { DocumentKind(it.value1()!!) to it.value2() }

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}
