package pl.barometr.legislative.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.barometr.connectors.rcl.api.RclCatalogPage
import pl.barometr.connectors.rcl.api.RclChangeRegister
import pl.barometr.connectors.rcl.api.RclChildDirectory
import pl.barometr.connectors.rcl.api.RclFiledDocument
import pl.barometr.connectors.rcl.api.RclPageReader
import pl.barometr.connectors.rcl.api.RclProjectCard
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.legislative.internal.jooq.tables.references.ARCHIVE_WALK
import pl.barometr.legislative.internal.jooq.tables.references.CATALOG_FOLDER
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_FILING
import pl.barometr.shared.ContentHash
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.internal.FilesystemBlobStore
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading the catalog pages that were archived before anything read them.
 *
 * A page is stored when its bytes are new, so a stage nobody has touched never fires
 * the listener again — and what a ministry filed under it would stay unread for as long
 * as the draft is dormant. What is pinned here is the walk's own arithmetic: that it
 * gets through the archive, that it resumes rather than restarts, and that it stops
 * doing work when there is none.
 */
class ArchivedCatalogSweepTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val archive = FakeArchive()
    private val walks = ArchiveWalkRepository(dsl, clock)

    private lateinit var blobs: FilesystemBlobStore
    private lateinit var sweep: ArchivedCatalogSweep

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(DRAFT_FILING).execute()
        dsl.deleteFrom(CATALOG_FOLDER).execute()
        dsl.deleteFrom(ARCHIVE_WALK).execute()
        archive.clear()

        blobs = FilesystemBlobStore(blobRoot)
        sweep = ArchivedCatalogSweep(
            documents = archive,
            catalogs = CatalogPageRecorder(
                blobs,
                StubRclPages,
                ConsultationRepository(dsl, clock),
                DraftFilingRepository(dsl, clock),
            ),
            walks = walks,
            meters = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `a catalog page nothing listened to is read out of the archive`() {
        archived("projekt/ustawa/12409051/katalog/13196866")

        sweep.readWhatWasFiledBeforeAnybodyWasListening()

        assertEquals(
            listOf("Projekt ustawy.pdf"),
            dsl.selectFrom(DRAFT_FILING).fetch().map { it.fileName },
        )
        assertEquals(1, dsl.fetchCount(CATALOG_FOLDER))
    }

    /** The cursor is what keeps the next run from paying for this one all over again. */
    @Test
    fun `a second run resumes after the last page read`() {
        archived("projekt/ustawa/12409051/katalog/13196866")
        archived("projekt/ustawa/12409052/katalog/13196870")

        sweep.readWhatWasFiledBeforeAnybodyWasListening()
        val walked = archive.walks
        sweep.readWhatWasFiledBeforeAnybodyWasListening()

        // One page of the archive on the first run, then one empty page on the second:
        // the walk asked for what comes after and was told there is nothing.
        assertEquals(walked + 1, archive.walks)
        assertEquals(2, dsl.fetchCount(DRAFT_FILING))
    }

    /**
     * The state this spends nearly all its life in. A walk that had to recognise "done"
     * from the rows would re-read every empty folder in the archive, hourly, for ever.
     */
    @Test
    fun `a run with nothing new to read records no progress`() {
        sweep.readWhatWasFiledBeforeAnybodyWasListening()

        assertEquals(0, dsl.fetchCount(ARCHIVE_WALK))
    }

    /** A page whose bytes the archive has lost is a warning, not a walk that stops. */
    @Test
    fun `a page the store has lost does not hold the walk up`() {
        archived("projekt/ustawa/12409051/katalog/13196866", stored = false)
        archived("projekt/ustawa/12409052/katalog/13196870")

        sweep.readWhatWasFiledBeforeAnybodyWasListening()

        assertEquals(1, dsl.fetchCount(DRAFT_FILING))
        assertTrue(walks.resume("rcl-catalog") != null)
    }

    private fun archived(address: String, stored: Boolean = true) {
        val payload = "<html>$address</html>".toByteArray()
        val hash = if (stored) {
            blobs.store(BlobBucket.RAW, payload, "text/html").contentHash
        } else {
            ContentHash.of(payload)
        }

        archive.holds(address, DocumentKind("rcl-catalog"), contentHash = hash)
    }

    /**
     * Stands in for the connector's parser, as in [RclCatalogProjectorTest]: what it
     * really reads off a page is pinned in the connector's own parsing test.
     */
    private object StubRclPages : RclPageReader {
        override fun readProjectCard(page: ByteArray): RclProjectCard? = null

        override fun readCatalog(page: ByteArray) = RclCatalogPage(
            childDirectories = listOf(RclChildDirectory("13196867", "Projekt", null)),
            documents = listOf(
                RclFiledDocument(
                    documentId = String(page).hashCode().toString(),
                    catalogId = "13196867",
                    fileName = "Projekt ustawy.pdf",
                    href = "/docs/1",
                    author = null,
                    createdOn = LocalDate.of(2026, 4, 9),
                ),
            ),
        )

        override fun readChangeRegister(page: ByteArray) = RclChangeRegister(subject = null, changes = emptyList())
    }
}
