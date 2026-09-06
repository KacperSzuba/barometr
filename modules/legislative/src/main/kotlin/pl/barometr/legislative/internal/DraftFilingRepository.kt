package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import pl.barometr.connectors.rcl.api.RclFiledDocument
import pl.barometr.corpus.api.DocumentId
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_FILING
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * What is filed under a draft. SQL only.
 *
 * Two writers, one row, and neither waits for the other: the catalog page names a file
 * and the archived file itself says where corpus keeps it, and which of the two is read
 * first is decided by the order a walk happened to fetch pages in. So both writes are
 * upserts that touch only their own half — the columns the other writer owns are left
 * exactly as they are, whether they are filled or still null.
 */
@Repository
class DraftFilingRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * What the stage's catalog page says about a file: its name, who filed it, when.
     *
     * Restated on every reading of the page rather than inserted once, because a
     * ministry does rename a file, and the page is the only thing that ever says so.
     */
    fun recordFiling(projectId: String, filed: RclFiledDocument) {
        dsl.insertInto(DRAFT_FILING)
            .set(DRAFT_FILING.SOURCE_PROJECT, projectId)
            .set(DRAFT_FILING.SOURCE_DOCUMENT, filed.documentId)
            .set(DRAFT_FILING.SOURCE_CATALOG, filed.catalogId)
            .set(DRAFT_FILING.FILE_NAME, filed.fileName)
            .set(DRAFT_FILING.AUTHOR, filed.author)
            .set(DRAFT_FILING.FILED_ON, filed.createdOn)
            .set(DRAFT_FILING.KNOWN_AT, now())
            .onConflict(DRAFT_FILING.SOURCE_PROJECT, DRAFT_FILING.SOURCE_DOCUMENT)
            .doUpdate()
            .set(DRAFT_FILING.SOURCE_CATALOG, DSL.excluded(DRAFT_FILING.SOURCE_CATALOG))
            .set(DRAFT_FILING.FILE_NAME, DSL.excluded(DRAFT_FILING.FILE_NAME))
            .set(DRAFT_FILING.AUTHOR, DSL.excluded(DRAFT_FILING.AUTHOR))
            .set(DRAFT_FILING.FILED_ON, DSL.excluded(DRAFT_FILING.FILED_ON))
            .execute()
    }

    /**
     * Where corpus keeps the file, written when the file itself reaches the archive.
     *
     * The document id is the only column this writer owns. A file archived before its
     * catalog page has been read creates the row with no name — which the constraint
     * allows precisely for this case, and which the next reading of the page fills in.
     */
    fun recordArchivedFile(
        projectId: String,
        sourceDocument: String,
        catalogId: String,
        documentId: DocumentId,
    ) {
        dsl.insertInto(DRAFT_FILING)
            .set(DRAFT_FILING.SOURCE_PROJECT, projectId)
            .set(DRAFT_FILING.SOURCE_DOCUMENT, sourceDocument)
            .set(DRAFT_FILING.SOURCE_CATALOG, catalogId)
            .set(DRAFT_FILING.DOCUMENT_ID, documentId.value)
            .set(DRAFT_FILING.KNOWN_AT, now())
            .onConflict(DRAFT_FILING.SOURCE_PROJECT, DRAFT_FILING.SOURCE_DOCUMENT)
            .doUpdate()
            .set(DRAFT_FILING.DOCUMENT_ID, DSL.excluded(DRAFT_FILING.DOCUMENT_ID))
            .execute()
    }

    /**
     * A draft's filings, newest first, capped.
     *
     * Capped rather than paged: a draft that has been through every stage carries a few
     * dozen files, a redrafted one under a hundred, and a card is not the place to walk
     * a thousand. The limit is the caller's, so the number and the reason for it live
     * beside the card that chose them.
     */
    fun filingsOf(projectId: String, limit: Int): List<DraftFiling> =
        dsl.select(
            DRAFT_FILING.DOCUMENT_ID,
            DRAFT_FILING.FILE_NAME,
            DRAFT_FILING.SOURCE_CATALOG,
            DRAFT_FILING.AUTHOR,
            DRAFT_FILING.FILED_ON,
        )
            .from(DRAFT_FILING)
            .where(DRAFT_FILING.SOURCE_PROJECT.eq(projectId))
            .orderBy(DRAFT_FILING.FILED_ON.desc().nullsLast(), DRAFT_FILING.SOURCE_DOCUMENT.desc())
            .limit(limit)
            .fetch { record ->
                DraftFiling(
                    documentId = record[DRAFT_FILING.DOCUMENT_ID]?.let(::DocumentId),
                    fileName = record[DRAFT_FILING.FILE_NAME],
                    catalogId = record[DRAFT_FILING.SOURCE_CATALOG]!!,
                    author = record[DRAFT_FILING.AUTHOR],
                    filedOn = record[DRAFT_FILING.FILED_ON],
                )
            }

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}
