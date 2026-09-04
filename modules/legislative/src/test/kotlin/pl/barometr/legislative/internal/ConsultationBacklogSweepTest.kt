package pl.barometr.legislative.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.barometr.connectors.rcl.api.RclCatalogPage
import pl.barometr.connectors.rcl.api.RclChangeRegister
import pl.barometr.connectors.rcl.api.RclFiledDocument
import pl.barometr.connectors.rcl.api.RclPageReader
import pl.barometr.connectors.rcl.api.RclProjectCard
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.CATALOG_FOLDER
import pl.barometr.legislative.internal.jooq.tables.references.CONSULTATION
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.shared.Ids
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.internal.FilesystemBlobStore
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Going back to the archive for the letters no arriving event ever dated.
 *
 * This is the path that decides whether the feature works at all on a system that has
 * been ingesting for months: every letter already archived had its text derived before
 * there was anything listening, and a content-addressed archive never produces a second
 * version of a PDF nobody edits. Without this, those consultations stay blank for good.
 */
class ConsultationBacklogSweepTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val archive = FakeArchive()

    private val consultations = ConsultationRepository(dsl, clock)

    private var draftId = DraftId(Ids.next())
    private lateinit var blobs: FilesystemBlobStore
    private lateinit var sweep: ConsultationBacklogSweep

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(CATALOG_FOLDER).execute()
        dsl.deleteFrom(CONSULTATION).execute()
        dsl.deleteFrom(DRAFT).execute()
        archive.clear()

        draftId = DraftRepository(dsl, clock).insertDraft(
            DraftFromRegister(
                title = "Projekt ustawy o zmianie ustawy o kredycie konsumenckim",
                initiator = DraftInitiator.GOVERNMENT,
                term = 10,
                startedOn = LocalDate.of(2026, 4, 9),
            ),
        )

        blobs = FilesystemBlobStore(blobRoot)
        sweep = ConsultationBacklogSweep(
            consultations = consultations,
            documents = archive,
            blobs = blobs,
            pages = StubRclPages,
            terms = ConsultationTerms(ConsultationLetterReader(), SimpleMeterRegistry()),
            meters = SimpleMeterRegistry(),
            clock = clock,
        )
    }

    @Test
    fun `a letter archived before anything was listening still dates its consultation`() {
        consultations.openConsultation(draftId, STAGE, CARD)
        archiveCatalogPage()
        archiveText(LETTER, letter())

        sweep.dateConsultationsTheArchiveCanAnswer()

        val row = assertNotNull(dsl.selectFrom(CONSULTATION).fetchOne())
        assertEquals(LocalDate.of(2026, 4, 30), row.closesOn)
        assertEquals(21, row.daysAllowed)
        assertNotNull(row.statedBy, "and the version it was read from is cited")
    }

    /**
     * A ministry can file a draft for comment without saying anywhere how long there is
     * to reply, so this queue always has members. Reading their dozen documents every
     * half hour would be most of what the sweep ever did.
     */
    @Test
    fun `a consultation the archive cannot date is not read again the same day`() {
        consultations.openConsultation(draftId, STAGE, CARD)
        archiveCatalogPage()
        archiveText(LETTER, "Projekt ustawy o zmianie ustawy. Art. 1. W ustawie wprowadza się zmiany.")

        sweep.dateConsultationsTheArchiveCanAnswer()
        val afterFirst = archive.lookups
        sweep.dateConsultationsTheArchiveCanAnswer()

        assertEquals(afterFirst, archive.lookups, "the same documents were not read twice in one day")
        assertNull(dsl.selectFrom(CONSULTATION).fetchOne()?.closesOn)
    }

    /** A day later the letter the ministry forgot may have been filed. */
    @Test
    fun `a consultation left undated is looked for again the next day`() {
        consultations.openConsultation(draftId, STAGE, CARD)
        archiveCatalogPage()
        archiveText(LETTER, "Projekt ustawy o zmianie ustawy.")

        sweep.dateConsultationsTheArchiveCanAnswer()
        archiveText(LETTER, letter())
        clock.advanceBy(Duration.ofDays(2))
        sweep.dateConsultationsTheArchiveCanAnswer()

        assertEquals(LocalDate.of(2026, 4, 30), dsl.selectFrom(CONSULTATION).fetchOne()?.closesOn)
    }

    @Test
    fun `a consultation that already has a date is not swept`() {
        val consultation = consultations.openConsultation(draftId, STAGE, CARD)
        consultations.recordTerm(
            consultation,
            ConsultationFact(
                opensOn = LocalDate.of(2026, 4, 9),
                closesOn = LocalDate.of(2026, 4, 30),
                daysAllowed = 21,
                submissionAddress = null,
                quote = "w terminie 21 dni",
                charStart = 0,
                charEnd = 17,
                statedIn = DocumentId(Ids.next()),
                statedBy = DocumentVersionId(Ids.next()),
            ),
        )
        archiveCatalogPage()

        sweep.dateConsultationsTheArchiveCanAnswer()

        assertEquals(emptyList(), archive.lookups, "nothing to ask the archive about")
    }

    /**
     * A row opened before the address column existed cannot say where to look. The next
     * reading of its card fills the address in; sweeping blind would mean guessing which
     * kind of draft it is, and guessing is how this system stops being trustworthy.
     */
    @Test
    fun `a consultation with no archive address is left for its card to fill in`() {
        consultations.openConsultation(draftId, STAGE, CARD)
        dsl.update(CONSULTATION).set(CONSULTATION.SOURCE_ADDRESS, null as String?).execute()

        sweep.dateConsultationsTheArchiveCanAnswer()

        assertEquals(emptyList(), archive.lookups)
    }

    /**
     * The draft and its impact assessment are read first and say nothing about a term;
     * a scan with no text layer says nothing at all. Neither stops the letter behind
     * them from being found.
     */
    @Test
    fun `documents that say nothing are passed over on the way to the one that does`() {
        consultations.openConsultation(draftId, STAGE, CARD)
        archiveCatalogPage()
        archiveText(DRAFT_TEXT, "Art. 1. W ustawie z dnia 12 maja 2011 r. o kredycie konsumenckim wprowadza się zmiany.")
        archive.holds(addressOf(SCAN), textHash = null)
        archiveText(LETTER, letter())

        sweep.dateConsultationsTheArchiveCanAnswer()

        assertEquals(LocalDate.of(2026, 4, 30), dsl.selectFrom(CONSULTATION).fetchOne()?.closesOn)
    }

    private fun archiveCatalogPage() {
        val stored = blobs.store(BlobBucket.RAW, "<html>the catalog as RPL served it</html>".toByteArray(), "text/html")
        archive.holds(RclCatalogAddress.catalogPageAt(CARD, STAGE).value, contentHash = stored.contentHash)
    }

    private fun archiveText(documentId: String, text: String) {
        val stored = blobs.store(BlobBucket.DERIVED, text.toByteArray(), "text/plain; charset=utf-8")
        archive.holds(addressOf(documentId), textHash = stored.contentHash)
    }

    private fun addressOf(documentId: String) =
        RclCatalogAddress.filedDocumentAt(CARD, LETTERS_FOLDER, documentId).value

    private fun letter() = """
        Warszawa, dnia 9 kwietnia 2026 r.

        MINISTER SPRAWIEDLIWOŚCI

        Uprzejmie przekazuję w załączeniu projekt ustawy z prośbą o zgłoszenie uwag
        w terminie 21 dni od dnia otrzymania niniejszego pisma.
    """.trimIndent()

    /**
     * The stage's page, rendering its whole subtree: the draft, a scan, and the letter
     * that opened consultation — in the order RPL lists them.
     */
    private object StubRclPages : RclPageReader {
        override fun readProjectCard(page: ByteArray): RclProjectCard? = null

        override fun readCatalog(page: ByteArray) = RclCatalogPage(
            childDirectories = emptyList(),
            documents = listOf(DRAFT_TEXT, SCAN, LETTER).map {
                RclFiledDocument(
                    documentId = it,
                    catalogId = LETTERS_FOLDER,
                    fileName = "$it.pdf",
                    href = "/docs//1/12409051/$STAGE/$LETTERS_FOLDER/dokument$it.pdf",
                    author = "Minister Sprawiedliwości",
                    createdOn = LocalDate.of(2026, 4, 9),
                )
            },
        )

        /** No register is read here; what these tests need is the page above it. */
        override fun readChangeRegister(page: ByteArray) = RclChangeRegister(subject = null, changes = emptyList())
    }

    private companion object {
        const val CARD = "projekt/ustawa/12409051"
        const val STAGE = "13196866"
        const val LETTERS_FOLDER = "13196868"
        const val DRAFT_TEXT = "770751"
        const val SCAN = "777916"
        const val LETTER = "778141"
    }
}
