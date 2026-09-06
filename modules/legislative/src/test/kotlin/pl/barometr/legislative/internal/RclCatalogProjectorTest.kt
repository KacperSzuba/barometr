package pl.barometr.legislative.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.barometr.connectors.rcl.api.RclCatalogPage
import pl.barometr.connectors.rcl.api.RclChangeRegister
import pl.barometr.connectors.rcl.api.RclChildDirectory
import pl.barometr.connectors.rcl.api.RclFiledDocument
import pl.barometr.connectors.rcl.api.RclPageReader
import pl.barometr.connectors.rcl.api.RclProjectCard
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.legislative.internal.jooq.tables.references.CATALOG_FOLDER
import pl.barometr.legislative.internal.jooq.tables.references.CONSULTATION
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_FILING
import pl.barometr.shared.ContentHash
import pl.barometr.shared.Ids
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.SourceId
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.internal.FilesystemBlobStore
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a stage's catalog page states, read from the page that states it.
 *
 * RPL renders a stage's whole subtree inline: the five folders it names are the only
 * place a filed letter is ever said to belong to the stage above it, and the files it
 * lists are the only place any of them is ever given a name.
 */
class RclCatalogProjectorTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private lateinit var blobs: FilesystemBlobStore
    private lateinit var pages: StubRclPages
    private lateinit var projector: RclCatalogProjector

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(CATALOG_FOLDER).execute()
        dsl.deleteFrom(DRAFT_FILING).execute()
        dsl.deleteFrom(CONSULTATION).execute()
        dsl.deleteFrom(DRAFT).execute()

        blobs = FilesystemBlobStore(blobRoot)
        pages = StubRclPages()
        projector = RclCatalogProjector(
            CatalogPageRecorder(blobs, pages, ConsultationRepository(dsl, clock), DraftFilingRepository(dsl, clock)),
        )
    }

    @Test
    fun `a catalog page records the folders inside it`() {
        projector.recordWhatTheCatalogHolds(archivedCatalog())

        assertEquals(
            mapOf("13196867" to "13196866", "13196868" to "13196866"),
            dsl.selectFrom(CATALOG_FOLDER).fetch().associate { it.catalogId to it.parentCatalogId },
        )
    }

    /** The page is re-read every time anything beneath it changes; a folder does not move. */
    @Test
    fun `a page read twice records each folder once`() {
        projector.recordWhatTheCatalogHolds(archivedCatalog())
        projector.recordWhatTheCatalogHolds(archivedCatalog())

        assertEquals(2, dsl.fetchCount(CATALOG_FOLDER))
    }

    @Test
    fun `a catalog page names the files filed under the draft`() {
        projector.recordWhatTheCatalogHolds(archivedCatalog())

        val filed = dsl.selectFrom(DRAFT_FILING).orderBy(DRAFT_FILING.SOURCE_DOCUMENT).fetch()

        assertEquals(listOf("12409051", "12409051"), filed.map { it.sourceProject })
        assertEquals(listOf("Projekt ustawy.pdf", "Pismo kierujące.pdf"), filed.map { it.fileName })
        assertEquals(listOf("13196867", "13196868"), filed.map { it.sourceCatalog })
        assertEquals(listOf(LocalDate.of(2026, 4, 9), null), filed.map { it.filedOn })
        // The page says what a file is called and nothing about where it is archived.
        assertNull(filed.first().documentId)
    }

    /** A ministry renames a file; the page it is listed on is the only thing that says so. */
    @Test
    fun `a renamed file is restated rather than added`() {
        projector.recordWhatTheCatalogHolds(archivedCatalog())
        pages.renameFirstFileTo("Projekt ustawy - po uwagach.pdf")
        projector.recordWhatTheCatalogHolds(archivedCatalog())

        assertEquals(2, dsl.fetchCount(DRAFT_FILING))
        assertEquals(
            "Projekt ustawy - po uwagach.pdf",
            dsl.selectFrom(DRAFT_FILING).orderBy(DRAFT_FILING.SOURCE_DOCUMENT).fetch().first().fileName,
        )
    }

    @Test
    fun `a page that is not a catalog is not read`() {
        projector.recordWhatTheCatalogHolds(archivedCatalog().copy(kind = DocumentKind("rcl-project")))

        assertEquals(0, dsl.fetchCount(CATALOG_FOLDER))
    }

    /**
     * A catalog's change register is a catalog's address with `/rejestr` on the end. A
     * prefix match would read the two as the same page and file the folders of one
     * under the id of the other.
     */
    @Test
    fun `a change register is not mistaken for the catalog it belongs to`() {
        projector.recordWhatTheCatalogHolds(
            archivedCatalog(externalId = ExternalId("projekt/ustawa/12409051/katalog/13196866/rejestr")),
        )

        assertEquals(0, dsl.fetchCount(CATALOG_FOLDER))
    }

    /** The archive has lost the bytes; that is a warning, not a failed delivery. */
    @Test
    fun `a page whose bytes are gone records nothing`() {
        projector.recordWhatTheCatalogHolds(archivedCatalog().copy(contentHash = ContentHash.of(byteArrayOf(9))))

        assertEquals(0, dsl.fetchCount(CATALOG_FOLDER))
    }

    private fun archivedCatalog(
        externalId: ExternalId = ExternalId("projekt/ustawa/12409051/katalog/13196866"),
    ): DocumentVersionRecorded {
        val stored = blobs.store(BlobBucket.RAW, "<html>the catalog as RPL served it</html>".toByteArray(), "text/html")

        return DocumentVersionRecorded(
            documentId = DocumentId(Ids.next()),
            versionId = DocumentVersionId(Ids.next()),
            sourceId = SourceId(Ids.next()),
            connectorId = ConnectorId("rcl"),
            externalId = externalId,
            kind = DocumentKind("rcl-catalog"),
            contentHash = stored.contentHash,
            versionNo = 1,
            occurredAt = clock.instant(),
        )
    }

    /**
     * Stands in for the connector's parser. That it really produces these folders from
     * the real page is pinned in the connector's own parsing test, against a fixture of
     * catalog 13196866.
     */
    private class StubRclPages : RclPageReader {
        private var billName = "Projekt ustawy.pdf"

        fun renameFirstFileTo(name: String) {
            billName = name
        }

        override fun readProjectCard(page: ByteArray): RclProjectCard? = null

        override fun readCatalog(page: ByteArray) = RclCatalogPage(
            childDirectories = listOf(
                RclChildDirectory("13196867", "Projekt", null),
                RclChildDirectory("13196868", "Pisma kierujące projekt do konsultacji publicznych", null),
            ),
            documents = listOf(
                RclFiledDocument(
                    documentId = "778141",
                    catalogId = "13196867",
                    fileName = billName,
                    href = "/docs/778141",
                    author = "Ministerstwo Finansów",
                    createdOn = LocalDate.of(2026, 4, 9),
                ),
                RclFiledDocument(
                    documentId = "778142",
                    catalogId = "13196868",
                    fileName = "Pismo kierujące.pdf",
                    href = "/docs/778142",
                    author = null,
                    createdOn = null,
                ),
            ),
        )

        /** No register is read here; what these tests need is the page above it. */
        override fun readChangeRegister(page: ByteArray) = RclChangeRegister(subject = null, changes = emptyList())
    }
}
