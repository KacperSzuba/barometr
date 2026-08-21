package pl.barometr.legislative.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.barometr.connectors.rcl.api.RclPageReader
import pl.barometr.connectors.rcl.api.RclProjectCard
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
import pl.barometr.storage.internal.StorageProperties
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.nio.file.Path
import java.time.LocalDate
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

    private val dsl = PostgresTestDatabase.dsl()
    private val clock = TestClock()

    private lateinit var blobs: FilesystemBlobStore
    private lateinit var projector: RclCardProjector

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(STAGE_TRANSITION).execute()
        dsl.deleteFrom(DRAFT_IDENTIFIER).execute()
        dsl.deleteFrom(DRAFT).execute()

        blobs = FilesystemBlobStore(StorageProperties(blobRoot))
        projector = RclCardProjector(
            blobs = blobs,
            pages = StubRclPages,
            drafts = DraftRepository(dsl, clock),
            identifiers = DraftIdentifierRepository(dsl, clock),
            meters = SimpleMeterRegistry(),
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
     * The limit of what a card can honestly support. It lists eight stages with a
     * state each and, on the few that have moved, a last-modified stamp — which is not
     * the day a stage began. `stage_transition` answers what the status was on a given
     * day, and answering it from that stamp would be this system inventing a date.
     */
    @Test
    fun `no timeline is recorded, because the card carries no stage dates`() {
        projector.projectGovernmentDraft(archivedCard())

        assertEquals(0, dsl.fetchCount(STAGE_TRANSITION))
    }

    @Test
    fun `a card re-fetched every six hours produces one draft`() {
        projector.projectGovernmentDraft(archivedCard())
        projector.projectGovernmentDraft(archivedCard())

        assertEquals(1, dsl.fetchCount(DRAFT))
        assertEquals(2, dsl.fetchCount(DRAFT_IDENTIFIER))
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
            stages = emptyList(),
        )
    }
}
