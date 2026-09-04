package pl.barometr.legislative.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.barometr.connectors.rcl.api.RclCatalogPage
import pl.barometr.connectors.rcl.api.RclPageReader
import pl.barometr.connectors.rcl.api.RclProjectCard
import pl.barometr.connectors.rcl.api.RclStage
import pl.barometr.connectors.rcl.api.RclStageState
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.CATALOG_FOLDER
import pl.barometr.legislative.internal.jooq.tables.references.CONSULTATION
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_IDENTIFIER
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.internal.FilesystemBlobStore
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Opening consultations for the drafts whose cards stopped changing before this feature
 * existed.
 *
 * The half of the backlog that has to be cleared first: the letter sweep looks for
 * documents to date a consultation with, and a draft whose card was projected months
 * ago has no consultation for it to look at. On a system that has been ingesting since
 * spring, that is nearly every draft it holds.
 */
class ArchivedCardSweepTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val archive = FakeArchive()

    private val drafts = DraftRepository(dsl, clock)
    private val identifiers = DraftIdentifierRepository(dsl, clock)
    private val consultations = ConsultationRepository(dsl, clock)

    private var draftId = DraftId(java.util.UUID.randomUUID())
    private lateinit var blobs: FilesystemBlobStore
    private lateinit var sweep: ArchivedCardSweep

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(CATALOG_FOLDER).execute()
        dsl.deleteFrom(CONSULTATION).execute()
        dsl.deleteFrom(DRAFT_IDENTIFIER).execute()
        dsl.deleteFrom(DRAFT).execute()
        archive.clear()

        draftId = drafts.insertDraft(
            DraftFromRegister(
                title = "Projekt ustawy o zmianie ustawy o kredycie konsumenckim",
                initiator = DraftInitiator.GOVERNMENT,
                term = 10,
                startedOn = LocalDate.of(2026, 4, 9),
            ),
        )
        identifiers.claimForDraft(DraftIdentifierScheme.RCL_PROJECT, PROJECT_ID, draftId)

        blobs = FilesystemBlobStore(blobRoot)
        sweep = ArchivedCardSweep(
            documents = archive,
            blobs = blobs,
            pages = StubRclPages,
            drafts = drafts,
            consultations = ConsultationOpening(consultations),
            meters = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `a card nothing has re-read since it was archived opens its consultation`() {
        archiveCard()

        sweep.openConsultationsFromArchivedCards()

        val consultation = assertNotNull(dsl.selectFrom(CONSULTATION).fetchOne())
        assertEquals("13196866", consultation.sourceCatalog)
        assertEquals(CARD, consultation.sourceAddress, "and where to look for the letter that dates it")
        assertNull(consultation.closesOn, "which is the letter sweep's job, not this one's")
    }

    /**
     * The walk starts at the beginning of the archive every run, so what stops it doing
     * the same work for ever is the mark on the draft — not a cursor nobody would know
     * had gone stale.
     */
    @Test
    fun `a card already read is stepped over without being fetched again`() {
        archiveCard()
        sweep.openConsultationsFromArchivedCards()

        sweep.openConsultationsFromArchivedCards()

        assertEquals(1, dsl.fetchCount(CONSULTATION), "the second pass opened nothing further")
        assertNotNull(dsl.selectFrom(DRAFT).fetchOne()?.consultationsReadAt)
    }

    /**
     * A card this system holds no draft for has not been projected at all, and inventing
     * one here would be this sweep quietly doing the projector's job — with none of the
     * identifiers, stages or events that go with it.
     */
    @Test
    fun `a card whose draft was never projected is left alone`() {
        archive.holds(
            "projekt/ustawa/99999999",
            kind = DocumentKind("rcl-project"),
            contentHash = blobs.store(BlobBucket.RAW, CARD_BYTES, "text/html").contentHash,
        )

        sweep.openConsultationsFromArchivedCards()

        assertEquals(0, dsl.fetchCount(CONSULTATION))
    }

    /** Change registers and catalog pages are archived beside the cards and are not cards. */
    @Test
    fun `only cards are read`() {
        archive.holds(
            "projekt/ustawa/$PROJECT_ID/rejestr",
            kind = DocumentKind("rcl-change-register"),
            contentHash = blobs.store(BlobBucket.RAW, CARD_BYTES, "text/html").contentHash,
        )

        sweep.openConsultationsFromArchivedCards()

        assertEquals(0, dsl.fetchCount(CONSULTATION))
        assertNull(dsl.selectFrom(DRAFT).fetchOne()?.consultationsReadAt, "the draft has still not been read")
    }

    /**
     * A page the parser cannot make a card of is marked read all the same. The warning
     * names it; leaving it unmarked would have every later run fetch and re-parse it.
     */
    @Test
    fun `a card that cannot be read is marked rather than tripped over for ever`() {
        archive.holds(
            CARD,
            kind = DocumentKind("rcl-project"),
            contentHash = blobs.store(BlobBucket.RAW, "not a card at all".toByteArray(), "text/html").contentHash,
        )

        sweep.openConsultationsFromArchivedCards()

        assertEquals(0, dsl.fetchCount(CONSULTATION))
        assertNotNull(dsl.selectFrom(DRAFT).fetchOne()?.consultationsReadAt)
    }

    private fun archiveCard() {
        archive.holds(
            CARD,
            kind = DocumentKind("rcl-project"),
            contentHash = blobs.store(BlobBucket.RAW, CARD_BYTES, "text/html").contentHash,
        )
    }

    /**
     * Stands in for the connector's parser, which is why the port exists. The bytes
     * decide only whether it answers: anything but the archived card reads as a page
     * that is not one.
     */
    private object StubRclPages : RclPageReader {
        override fun readProjectCard(page: ByteArray): RclProjectCard? =
            if (!page.contentEquals(CARD_BYTES)) {
                null
            } else {
                RclProjectCard(
                    projectId = PROJECT_ID,
                    title = "Projekt ustawy o zmianie ustawy o kredycie konsumenckim",
                    metadata = emptyMap(),
                    programmeOfWorkUrl = null,
                    createdOn = LocalDate.of(2026, 4, 9),
                    stages = listOf(
                        RclStage("13196859", 1, "Uzgodnienia", RclStageState.DONE, null, isVisitable = true),
                        RclStage("13196866", 2, "Konsultacje publiczne", RclStageState.CURRENT, null, true),
                    ),
                )
            }

        override fun readCatalog(page: ByteArray) = RclCatalogPage(emptyList(), emptyList())
    }

    private companion object {
        const val PROJECT_ID = "12409051"
        const val CARD = "projekt/ustawa/$PROJECT_ID"
        val CARD_BYTES = "<html>the card as RPL served it</html>".toByteArray()
    }
}
