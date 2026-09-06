package pl.barometr.legislative.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_IDENTIFIER
import pl.barometr.legislative.internal.jooq.tables.references.VOTE
import pl.barometr.legislative.internal.jooq.tables.references.VOTE_PRINT
import pl.barometr.shared.ContentHash
import pl.barometr.shared.Ids
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.SourceId
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.internal.FilesystemBlobStore
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An archived voting becoming a vote, and finding the draft it decided — against a real
 * database, because most of what is being tested is what the schema refuses and what the
 * join finds: one row per voting however often a sitting is re-read, and a vote that
 * reaches its draft through the print's address rather than through a foreign key.
 */
class SejmVoteProjectorTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()

    private lateinit var blobs: FilesystemBlobStore
    private lateinit var meters: SimpleMeterRegistry
    private lateinit var votes: VoteRepository
    private lateinit var projector: SejmVoteProjector

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(VOTE_PRINT).execute()
        dsl.deleteFrom(VOTE).execute()
        dsl.deleteFrom(DRAFT_IDENTIFIER).execute()
        dsl.deleteFrom(DRAFT).execute()

        blobs = FilesystemBlobStore(blobRoot)
        meters = SimpleMeterRegistry()
        votes = VoteRepository(dsl, clock)
        projector = SejmVoteProjector(
            blobs = blobs,
            reader = SejmVoteReader(json),
            votes = votes,
            meters = meters,
        )
    }

    @Test
    fun `an archived voting becomes a vote with the numbers the register stated`() {
        projector.projectVote(archivedVoting("voting-1-3.json", "term10/proceeding/1/voting/3"))

        val vote = assertNotNull(dsl.selectFrom(VOTE).fetchOne())
        assertEquals(10, vote.term)
        assertEquals(1, vote.sitting)
        assertEquals(3, vote.votingNumber)
        assertEquals(272, vote.yes)
        assertEquals(27, vote.no)
        assertEquals(153, vote.abstained)
        assertEquals("ELECTRONIC", vote.method)
    }

    @Test
    fun `the prints the item named are recorded as the addresses the archive keeps them under`() {
        projector.projectVote(archivedVoting("voting-1-3.json", "term10/proceeding/1/voting/3"))

        assertEquals(
            listOf(3, 4, 5, 6, 7, 8).map { "term10/print/$it" },
            dsl.select(VOTE_PRINT.PRINT_ADDRESS).from(VOTE_PRINT).fetch { it.value1() }.sortedBy { it.length },
        )
    }

    /**
     * A sitting is re-read whenever anything in it moves, and Spring Modulith redelivers
     * a listener that threw. Neither may leave a second copy of a vote behind.
     */
    @Test
    fun `re-reading a sitting restates its votes rather than duplicating them`() {
        projector.projectVote(archivedVoting("voting-1-3.json", "term10/proceeding/1/voting/3"))
        projector.projectVote(archivedVoting("voting-1-3.json", "term10/proceeding/1/voting/3"))

        assertEquals(1, dsl.fetchCount(VOTE), "one row per voting of a sitting")
        assertEquals(6, dsl.fetchCount(VOTE_PRINT), "and one row per print it named")
    }

    /**
     * The ordering this schema is keyed around: votings and processes are separate walks,
     * so a vote is ordinarily archived before the draft it decided exists. The row is
     * written anyway and the draft finds it when it arrives.
     */
    @Test
    fun `a vote recorded before its draft existed is found once the draft arrives`() {
        projector.projectVote(archivedVoting("voting-1-3.json", "term10/proceeding/1/voting/3"))

        assertTrue(votes.votesCiting(DraftId(Ids.next()), limit = 10).isEmpty(), "no draft, no votes")

        val draftId = insertDraftPrinted("term10/print/5")

        val found = votes.votesCiting(draftId, limit = 10)
        assertEquals(1, found.size)
        assertEquals(272, found.single().yes)
    }

    @Test
    fun `a vote on a person is recorded and counted as citing nothing`() {
        projector.projectVote(archivedVoting("voting-1-1.json", "term10/proceeding/1/voting/1"))

        assertEquals(1, dsl.fetchCount(VOTE), "a vote on a Marshal is still a vote")
        assertEquals(0, dsl.fetchCount(VOTE_PRINT))
        assertEquals(1.0, meters.counter("legislative.vote.uncited").count())
    }

    @Test
    fun `a document of another kind is not read as a vote`() {
        val voting = archivedVoting("voting-1-3.json", "term10/print/3")

        projector.projectVote(voting.copy(kind = DocumentKind("print")))

        assertEquals(0, dsl.fetchCount(VOTE))
    }

    /** A draft whose print the Sejm has printed, addressed the way the register addresses it. */
    private fun insertDraftPrinted(address: String): DraftId {
        val draftId = DraftRepository(dsl, clock).insertDraft(
            DraftFromRegister(
                title = "Poselski projekt uchwały w sprawie wyboru Wicemarszałków Sejmu",
                initiator = DraftInitiator.DEPUTIES,
                term = 10,
                startedOn = LocalDate.parse("2023-11-13"),
                closedOn = null,
                outcome = null,
            ),
        )
        DraftIdentifierRepository(dsl, clock).claimForDraft(DraftIdentifierScheme.SEJM_PRINT, address, draftId)

        return draftId
    }

    private fun archivedVoting(fixture: String, address: String): DocumentVersionRecorded {
        val payload = requireNotNull(javaClass.getResourceAsStream("/fixtures/sejm/$fixture"))
            .use { it.readBytes() }
        blobs.store(BlobBucket.RAW, payload, "application/json")

        return DocumentVersionRecorded(
            documentId = DocumentId(Ids.next()),
            versionId = DocumentVersionId(Ids.next()),
            sourceId = SourceId(Ids.next()),
            connectorId = ConnectorId("sejm"),
            externalId = ExternalId(address),
            kind = DocumentKind("voting"),
            contentHash = ContentHash.of(payload),
            versionNo = 1,
            occurredAt = clock.instant(),
        )
    }
}
