package pl.barometr.legislative.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.barometr.connectors.rcl.api.RclCatalogPage
import pl.barometr.connectors.rcl.api.RclChangeRegister
import pl.barometr.connectors.rcl.api.RclChildDirectory
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
import pl.barometr.shared.ContentHash
import pl.barometr.shared.Ids
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.SourceId
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.internal.FilesystemBlobStore
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * The edge a deadline is matched along, read from the page that states it.
 *
 * RPL renders a stage's whole subtree inline, and the five folders it names are the
 * only place a filed letter is ever said to belong to the stage above it.
 */
class RclCatalogProjectorTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private lateinit var blobs: FilesystemBlobStore
    private lateinit var projector: RclCatalogProjector

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(CATALOG_FOLDER).execute()
        dsl.deleteFrom(CONSULTATION).execute()
        dsl.deleteFrom(DRAFT).execute()

        blobs = FilesystemBlobStore(blobRoot)
        projector = RclCatalogProjector(blobs, StubRclPages, ConsultationRepository(dsl, clock))
    }

    @Test
    fun `a catalog page records the folders inside it`() {
        projector.recordFoldersInsideCatalog(archivedCatalog())

        assertEquals(
            mapOf("13196867" to "13196866", "13196868" to "13196866"),
            dsl.selectFrom(CATALOG_FOLDER).fetch().associate { it.catalogId to it.parentCatalogId },
        )
    }

    /** The page is re-read every time anything beneath it changes; a folder does not move. */
    @Test
    fun `a page read twice records each folder once`() {
        projector.recordFoldersInsideCatalog(archivedCatalog())
        projector.recordFoldersInsideCatalog(archivedCatalog())

        assertEquals(2, dsl.fetchCount(CATALOG_FOLDER))
    }

    @Test
    fun `a page that is not a catalog is not read`() {
        projector.recordFoldersInsideCatalog(archivedCatalog().copy(kind = DocumentKind("rcl-project")))

        assertEquals(0, dsl.fetchCount(CATALOG_FOLDER))
    }

    /**
     * A catalog's change register is a catalog's address with `/rejestr` on the end. A
     * prefix match would read the two as the same page and file the folders of one
     * under the id of the other.
     */
    @Test
    fun `a change register is not mistaken for the catalog it belongs to`() {
        projector.recordFoldersInsideCatalog(
            archivedCatalog(externalId = ExternalId("projekt/ustawa/12409051/katalog/13196866/rejestr")),
        )

        assertEquals(0, dsl.fetchCount(CATALOG_FOLDER))
    }

    /** The archive has lost the bytes; that is a warning, not a failed delivery. */
    @Test
    fun `a page whose bytes are gone records nothing`() {
        projector.recordFoldersInsideCatalog(archivedCatalog().copy(contentHash = ContentHash.of(byteArrayOf(9))))

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
    private object StubRclPages : RclPageReader {
        override fun readProjectCard(page: ByteArray): RclProjectCard? = null

        override fun readCatalog(page: ByteArray) = RclCatalogPage(
            childDirectories = listOf(
                RclChildDirectory("13196867", "Projekt", null),
                RclChildDirectory("13196868", "Pisma kierujące projekt do konsultacji publicznych", null),
            ),
            documents = emptyList(),
        )

        /** No register is read here; what these tests need is the page above it. */
        override fun readChangeRegister(page: ByteArray) = RclChangeRegister(subject = null, changes = emptyList())
    }
}
