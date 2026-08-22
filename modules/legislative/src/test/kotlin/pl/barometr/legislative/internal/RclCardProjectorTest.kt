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
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.ingestion.api.ExternalId
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
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A government draft appearing in the system months before the Sejm prints it.
 *
 * That head start is what RPL is for, and this is the whole of what its card can
 * support: who filed it, what it is called, the numbers people quote it by, and the
 * day it entered the process. What the card cannot support — a timeline — is asserted
 * here too, because a stage recorded from a "last modified" stamp would be a date
 * this system invented.
 */
class RclCardProjectorTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private lateinit var blobs: FilesystemBlobStore
    private lateinit var projector: RclCardProjector

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(STAGE_TRANSITION).execute()
        dsl.deleteFrom(DRAFT_IDENTIFIER).execute()
        dsl.deleteFrom(DRAFT).execute()

        blobs = FilesystemBlobStore(blobRoot)
        projector = RclCardProjector(
            blobs = blobs,
            pages = StubRclPages,
            drafts = DraftRepository(dsl, clock),
            identifiers = DraftIdentifierRepository(dsl, clock),
            transitions = StageTransitionRepository(dsl, clock),
            events = RecordingEventPublisher(),
            meters = SimpleMeterRegistry(),
            clock = clock,
        )
    }

    @Test
    fun `a card becomes a government draft dated from the day it entered the process`() {
        projector.projectGovernmentDraft(archivedCard())

        val draft = assertNotNull(dsl.selectFrom(DRAFT).fetchOne())
        assertEquals("Projekt ustawy o zmianie ustawy o kredycie konsumenckim", draft.title)
        // RPL is the government's own process; everything on it is a government draft.
        assertEquals(DraftInitiator.GOVERNMENT.wireName, draft.initiator)
        assertEquals(10, draft.term, "the card writes the term in roman numerals")
        assertEquals(LocalDate.parse("2026-04-09"), draft.startedOn)
        assertNull(draft.outcome, "a card describes a draft that is still moving")
    }

    @Test
    fun `both numbers RPL states are recorded, and the project id is the claim`() {
        projector.projectGovernmentDraft(archivedCard())

        val identifiers = dsl.selectFrom(DRAFT_IDENTIFIER)
            .fetch()
            .associate { it.scheme to it.value }

        assertEquals(
            mapOf(
                DraftIdentifierScheme.RCL_PROJECT.wireName to "12409051",
                // What a person quoting the draft actually says.
                DraftIdentifierScheme.PROGRAMME_OF_WORK.wireName to "UD383",
            ),
            identifiers,
        )
    }

    /**
     * The limit of what a card can honestly support, and the one thing inside it.
     *
     * A card lists eight stages with a state each and, on the few that have moved, a
     * last-modified stamp — which is not the day a stage began, so no per-stage
     * timeline can come from it. What it does state is the day the draft entered the
     * process, and that single dated fact is recorded coarsely, with RPL's own word
     * for where the draft is beside it.
     */
    @Test
    fun `entering the process is dated, the stages inside it are not`() {
        projector.projectGovernmentDraft(archivedCard())

        val recorded = assertNotNull(dsl.selectFrom(STAGE_TRANSITION).fetchOne())
        assertEquals(LegislativeStage.GOVERNMENT_PROCESS.wireName, recorded.stage)
        // Compared as an instant: the driver hands the column back in the JVM's own
        // offset, and two renderings of one moment are not equal as OffsetDateTime.
        assertEquals(
            LocalDate.parse("2026-04-09").atStartOfDay(ZoneOffset.UTC).toInstant(),
            recorded.validFrom?.toInstant(),
        )
        assertNull(recorded.validTo, "a card never says a draft has left")
        assertEquals("Konsultacje publiczne", recorded.sourceLabel, "RPL's own word for where it is")
        assertEquals(1, dsl.fetchCount(STAGE_TRANSITION), "no per-stage timeline is invented")
    }

    @Test
    fun `a card re-fetched every six hours produces one draft`() {
        projector.projectGovernmentDraft(archivedCard())
        projector.projectGovernmentDraft(archivedCard())

        assertEquals(1, dsl.fetchCount(DRAFT))
        assertEquals(2, dsl.fetchCount(DRAFT_IDENTIFIER))
        assertEquals(1, dsl.fetchCount(STAGE_TRANSITION), "the same dated fact is not appended twice")
    }

    @Test
    fun `a page that is not a card is not projected`() {
        projector.projectGovernmentDraft(archivedCard().copy(kind = DocumentKind("rcl-change-register")))

        assertEquals(0, dsl.fetchCount(DRAFT))
    }

    private fun archivedCard(): DocumentVersionRecorded {
        val payload = "<html>the card as RPL served it</html>".toByteArray()
        val stored = blobs.store(BlobBucket.RAW, payload, "text/html")

        return DocumentVersionRecorded(
            documentId = DocumentId(Ids.next()),
            versionId = DocumentVersionId(Ids.next()),
            sourceId = SourceId(Ids.next()),
            connectorId = ConnectorId("rcl"),
            externalId = ExternalId("projekt/ustawa/12409051"),
            kind = DocumentKind("rcl-project"),
            contentHash = stored.contentHash,
            versionNo = 1,
            occurredAt = clock.instant(),
        )
    }

    /**
     * Stands in for the connector's parser, which is why the port exists: this context
     * needs a card, not a description of RPL's markup. That the parser really produces
     * this from the real page is pinned in the connector's own contract test.
     */
    private object StubRclPages : RclPageReader {
        override fun readProjectCard(page: ByteArray) = RclProjectCard(
            projectId = "12409051",
            title = "Projekt ustawy o zmianie ustawy o kredycie konsumenckim",
            metadata = mapOf(
                RclProjectCard.REGISTER_NUMBER to "UD383",
                RclProjectCard.TERM to "X",
                RclProjectCard.APPLICANT to "Minister Sprawiedliwości",
            ),
            programmeOfWorkUrl = null,
            createdOn = LocalDate.parse("2026-04-09"),
            stages = listOf(
                RclStage("13196859", 1, "Uzgodnienia", RclStageState.DONE, null, isVisitable = true),
                RclStage("13196866", 2, "Konsultacje publiczne", RclStageState.CURRENT, null, isVisitable = true),
                RclStage("13196868", 3, "Opiniowanie", RclStageState.NOT_STARTED, null, isVisitable = false),
            ),
        )

        /** Nothing here reads a catalog; a card is the whole of what is projected. */
        override fun readCatalog(page: ByteArray) = RclCatalogPage(emptyList(), emptyList())
    }

    /** Records what the projector announced; nothing here asserts on it yet. */
    private class RecordingEventPublisher : org.springframework.context.ApplicationEventPublisher {
        val published = mutableListOf<Any>()

        override fun publishEvent(event: Any) {
            published += event
        }
    }
}
