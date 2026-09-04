package pl.barometr.legislative.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.barometr.connectors.rcl.api.RclCatalogPage
import pl.barometr.connectors.rcl.api.RclChange
import pl.barometr.connectors.rcl.api.RclChangeKind
import pl.barometr.connectors.rcl.api.RclChangeRegister
import pl.barometr.connectors.rcl.api.RclPageReader
import pl.barometr.connectors.rcl.api.RclProjectCard
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.CONSULTATION
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_IDENTIFIER
import pl.barometr.legislative.internal.jooq.tables.references.STAGE_TRANSITION
import pl.barometr.shared.ContentHash
import pl.barometr.shared.Ids
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.SourceId
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.internal.FilesystemBlobStore
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The dated timeline a draft's card could never support.
 *
 * A card marks a stage as current and stamps it with the day it was last touched,
 * which is not the day it began; this page says the draft moved to "3. Konsultacje
 * publiczne" at 15:26 on the ninth of April. What is asserted here is that the
 * difference survives into the record: real periods, in order, in RPL's own words,
 * with the last one left open because the draft is still in it.
 */
class RclChangeRegisterProjectorTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private val identifiers = DraftIdentifierRepository(dsl, clock)

    private var draftId = DraftId(Ids.next())
    private lateinit var blobs: FilesystemBlobStore
    private lateinit var projector: RclChangeRegisterProjector

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(CONSULTATION).execute()
        dsl.deleteFrom(STAGE_TRANSITION).execute()
        dsl.deleteFrom(DRAFT_IDENTIFIER).execute()
        dsl.deleteFrom(DRAFT).execute()

        draftId = DraftRepository(dsl, clock).insertDraft(
            DraftFromRegister(
                title = "Projekt ustawy o zmianie ustawy o kredycie konsumenckim",
                initiator = DraftInitiator.GOVERNMENT,
                term = 10,
                startedOn = LocalDate.of(2026, 4, 9),
            ),
        )
        identifiers.claimForDraft(DraftIdentifierScheme.RCL_PROJECT, PROJECT_ID, draftId)

        blobs = FilesystemBlobStore(blobRoot)
        projector = RclChangeRegisterProjector(
            blobs = blobs,
            pages = StubRclPages,
            drafts = identifiers,
            transitions = StageTransitionRepository(dsl, clock),
            meters = SimpleMeterRegistry(),
            clock = clock,
        )
    }

    @Test
    fun `each move the register timed becomes a stage of its own`() {
        projector.projectStageTimeline(archivedRegister())

        assertEquals(
            listOf(
                LegislativeStage.INTER_MINISTERIAL_AGREEMENT.wireName,
                LegislativeStage.PUBLIC_CONSULTATION.wireName,
                LegislativeStage.OPINION.wireName,
                LegislativeStage.UNKNOWN.wireName,
            ),
            dsl.selectFrom(STAGE_TRANSITION).orderBy(STAGE_TRANSITION.ORDINAL).fetch().map { it.stage },
        )
    }

    /**
     * The reason these pages are read at all. A card would have given the ninth of
     * April; the register gives 15:26 that afternoon, and `valid_from` wants the second.
     */
    @Test
    fun `a stage begins at the minute the register says it did`() {
        projector.projectStageTimeline(archivedRegister())

        val consultation = dsl.selectFrom(STAGE_TRANSITION)
            .where(STAGE_TRANSITION.STAGE.eq(LegislativeStage.PUBLIC_CONSULTATION.wireName))
            .fetchOne()

        // 15:26 in Warsaw, which in April is two hours ahead of UTC.
        assertEquals(Instant.parse("2026-04-09T13:26:00Z"), consultation?.validFrom?.toInstant())
    }

    /**
     * The register states beginnings and never endings, which is the same thing said
     * differently: a draft leaves a stage by arriving at the next one, and the last one
     * on the list is where it still is.
     */
    @Test
    fun `a stage ends where the next one begins, and the last stays open`() {
        projector.projectStageTimeline(archivedRegister())

        val stages = dsl.selectFrom(STAGE_TRANSITION).orderBy(STAGE_TRANSITION.ORDINAL).fetch()

        assertEquals(
            stages[1].validFrom?.toInstant(),
            stages[0].validTo?.toInstant(),
            "one stage's end is the next one's beginning",
        )
        assertNull(stages.last().validTo, "the draft is still where the register last put it")
    }

    /**
     * RPL's checklist runs through steps this model has no name for, and a history with
     * a gap in it is worse than one saying so. The label is what makes the gap visible.
     */
    @Test
    fun `a stage this model cannot name keeps the register's own words`() {
        projector.projectStageTimeline(archivedRegister())

        val unnamed = assertNotNull(
            dsl.selectFrom(STAGE_TRANSITION)
                .where(STAGE_TRANSITION.STAGE.eq(LegislativeStage.UNKNOWN.wireName))
                .fetchOne(),
        )

        assertEquals("7. Komisja Prawnicza", unnamed.sourceLabel, "recorded, and visible for being unnamed")
    }

    /** A register re-read every six hours restates the same moves, and they are one history. */
    @Test
    fun `a register read twice records one timeline`() {
        projector.projectStageTimeline(archivedRegister())
        projector.projectStageTimeline(archivedRegister())

        assertEquals(4, dsl.fetchCount(STAGE_TRANSITION))
    }

    @Test
    fun `a page that is not a project's register is not read`() {
        projector.projectStageTimeline(archivedRegister().copy(kind = DocumentKind("rcl-catalog")))

        assertEquals(0, dsl.fetchCount(STAGE_TRANSITION))
    }

    @Test
    fun `a register whose draft this system does not hold is passed over`() {
        projector.projectStageTimeline(
            archivedRegister(externalId = ExternalId("projekt/ustawa/99999999/rejestr")),
        )

        assertEquals(0, dsl.fetchCount(STAGE_TRANSITION))
    }

    /** The archive has lost the bytes; that is a warning, not a failed delivery. */
    @Test
    fun `a register whose bytes are gone records nothing`() {
        projector.projectStageTimeline(archivedRegister().copy(contentHash = ContentHash.of(byteArrayOf(9))))

        assertEquals(0, dsl.fetchCount(STAGE_TRANSITION))
    }

    private fun archivedRegister(
        externalId: ExternalId = ExternalId("projekt/ustawa/$PROJECT_ID/rejestr"),
    ): DocumentVersionRecorded {
        val stored = blobs.store(BlobBucket.RAW, "<html>the register as RPL served it</html>".toByteArray(), "text/html")

        return DocumentVersionRecorded(
            documentId = DocumentId(Ids.next()),
            versionId = DocumentVersionId(Ids.next()),
            sourceId = SourceId(Ids.next()),
            connectorId = ConnectorId("rcl"),
            externalId = externalId,
            kind = DocumentKind("rcl-change-register"),
            contentHash = stored.contentHash,
            versionNo = 1,
            occurredAt = clock.instant(),
        )
    }

    /**
     * Stands in for the connector's parser, with the three moves the real fixture holds.
     * That the parser really reads them off RPL's markup — including that a stage change
     * is worded as a change to the attribute "nazwa etapu" — is pinned in the
     * connector's own parsing test.
     */
    private object StubRclPages : RclPageReader {
        override fun readProjectCard(page: ByteArray): RclProjectCard? = null

        override fun readCatalog(page: ByteArray) = RclCatalogPage(emptyList(), emptyList())

        override fun readChangeRegister(page: ByteArray) = RclChangeRegister(
            subject = null,
            changes = listOf(
                moved("2. Uzgodnienia", LocalDateTime.of(2026, 4, 9, 15, 14)),
                moved("3. Konsultacje publiczne", LocalDateTime.of(2026, 4, 9, 15, 26)),
                moved("4. Opiniowanie", LocalDateTime.of(2026, 5, 12, 9, 3)),
                // A step RPL's checklist has and this model has no name for.
                moved("7. Komisja Prawnicza", LocalDateTime.of(2026, 6, 1, 11, 40)),
            ),
        )

        private fun moved(to: String, at: LocalDateTime) = RclChange(
            occurredAt = at,
            author = "Aneta Sobolewska",
            description = "zmiana atrybutu",
            kind = RclChangeKind.ATTRIBUTE_CHANGED,
            attribute = "nazwa etapu",
            newValue = to,
        )
    }

    private companion object {
        const val PROJECT_ID = "12409051"
    }
}
