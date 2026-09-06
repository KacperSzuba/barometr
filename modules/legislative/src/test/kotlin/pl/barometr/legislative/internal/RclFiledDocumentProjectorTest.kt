package pl.barometr.legislative.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.connectors.rcl.api.RclFiledDocument
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_FILING
import pl.barometr.shared.ContentHash
import pl.barometr.shared.Ids
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.SourceId
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The half of a filing that says where to read it.
 *
 * A filing is stated by two pages that are fetched in whatever order a walk reaches
 * them: the catalog page names the file, the archived file carries the address corpus
 * keeps it under. So the pair is tested in both orders — one of them is the ordering
 * this feature would silently lose everything on if the writes were not upserts.
 */
class RclFiledDocumentProjectorTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val filings = DraftFilingRepository(dsl, clock)
    private val projector = RclFiledDocumentProjector(filings)

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(DRAFT_FILING).execute()
    }

    @Test
    fun `an archived file is recorded against the draft it was filed under`() {
        val documentId = DocumentId(Ids.next())

        projector.recordArchivedFiling(archivedFile(documentId))

        val filed = dsl.selectFrom(DRAFT_FILING).fetchSingle()
        assertEquals("12409051", filed.sourceProject)
        assertEquals("778141", filed.sourceDocument)
        assertEquals("13196867", filed.sourceCatalog)
        assertEquals(documentId.value, filed.documentId)
        // Nothing in the bytes or the address says what the file is called.
        assertNull(filed.fileName)
    }

    @Test
    fun `a file archived before its catalog page is read is named when the page arrives`() {
        val documentId = DocumentId(Ids.next())

        projector.recordArchivedFiling(archivedFile(documentId))
        filings.recordFiling("12409051", filed())

        val filed = dsl.selectFrom(DRAFT_FILING).fetchSingle()
        assertEquals("Projekt ustawy.pdf", filed.fileName)
        assertEquals(documentId.value, filed.documentId)
    }

    @Test
    fun `a file named before it is archived keeps its name when the archive answers`() {
        val documentId = DocumentId(Ids.next())

        filings.recordFiling("12409051", filed())
        projector.recordArchivedFiling(archivedFile(documentId))

        val filed = dsl.selectFrom(DRAFT_FILING).fetchSingle()
        assertEquals("Projekt ustawy.pdf", filed.fileName)
        assertEquals(LocalDate.of(2026, 4, 9), filed.filedOn)
        assertEquals(documentId.value, filed.documentId)
    }

    /** The card of the draft, the register beneath it, the folder itself: pages, not filings. */
    @Test
    fun `a page about a draft is not recorded as a file filed under it`() {
        projector.recordArchivedFiling(
            archivedFile(DocumentId(Ids.next()), ExternalId("projekt/ustawa/12409051/katalog/13196867")),
        )

        assertEquals(0, dsl.fetchCount(DRAFT_FILING))
    }

    @Test
    fun `a document of another kind is not read`() {
        projector.recordArchivedFiling(archivedFile(DocumentId(Ids.next())).copy(kind = DocumentKind("rcl-catalog")))

        assertEquals(0, dsl.fetchCount(DRAFT_FILING))
    }

    private fun filed() = RclFiledDocument(
        documentId = "778141",
        catalogId = "13196867",
        fileName = "Projekt ustawy.pdf",
        href = "/docs/778141",
        author = "Ministerstwo Finansów",
        createdOn = LocalDate.of(2026, 4, 9),
    )

    private fun archivedFile(
        documentId: DocumentId,
        externalId: ExternalId = ExternalId("projekt/ustawa/12409051/katalog/13196867/dokument/778141"),
    ) = DocumentVersionRecorded(
        documentId = documentId,
        versionId = DocumentVersionId(Ids.next()),
        sourceId = SourceId(Ids.next()),
        connectorId = ConnectorId("rcl"),
        externalId = externalId,
        kind = DocumentKind("rcl-filed-document"),
        contentHash = ContentHash.of(byteArrayOf(1, 2, 3)),
        versionNo = 1,
        occurredAt = clock.instant(),
    )
}
