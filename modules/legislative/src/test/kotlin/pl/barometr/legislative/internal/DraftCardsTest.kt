package pl.barometr.legislative.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.connectors.rcl.api.RclFiledDocument
import pl.barometr.corpus.api.DocumentId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_FILING
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_IDENTIFIER
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a draft's card says was filed under it.
 *
 * The rest of the card — the status, the medians, the other register — is pinned by the
 * engines that compute it. What is tested here is the join those engines do not do:
 * from a draft, through the id RPL knows it by, to the documents a ministry filed.
 */
class DraftCardsTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private val drafts = DraftRepository(dsl, clock)
    private val identifiers = DraftIdentifierRepository(dsl, clock)
    private val filings = DraftFilingRepository(dsl, clock)
    private val cards = DraftCards(
        drafts,
        StageTransitionRepository(dsl, clock),
        StagePaceRepository(dsl),
        DraftContinuationRepository(dsl, clock),
        identifiers,
        filings,
        DraftStatusEngine(clock),
    )

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(DRAFT_FILING).execute()
        dsl.deleteFrom(DRAFT_IDENTIFIER).execute()
        dsl.deleteFrom(DRAFT).execute()
    }

    @Test
    fun `a card carries what the ministry filed, newest first`() {
        val draftId = governmentDraft()
        filings.recordFiling(PROJECT, filed("778141", "Projekt ustawy.pdf", LocalDate.of(2026, 4, 9)))
        filings.recordFiling(PROJECT, filed("778142", "Ocena skutków regulacji.pdf", LocalDate.of(2026, 4, 20)))

        val filed = cards.cardFor(draftId).filings

        assertEquals(
            listOf("Ocena skutków regulacji.pdf", "Projekt ustawy.pdf"),
            filed.map { it.fileName },
        )
    }

    /**
     * The id a reader follows to read the file. Null while RPL lists a document the
     * archive has not fetched, and that is reported rather than hidden.
     */
    @Test
    fun `a filing carries the document that reaches it, once the archive holds one`() {
        val draftId = governmentDraft()
        val documentId = DocumentId(Ids.next())
        filings.recordFiling(PROJECT, filed("778141", "Projekt ustawy.pdf", LocalDate.of(2026, 4, 9)))
        filings.recordFiling(PROJECT, filed("778142", "Załącznik.zip", null))
        filings.recordArchivedFile(PROJECT, "778141", "13196867", documentId)

        val filed = cards.cardFor(draftId).filings.associateBy { it.fileName }

        assertEquals(documentId, filed.getValue("Projekt ustawy.pdf").documentId)
        assertNull(filed.getValue("Załącznik.zip").documentId)
    }

    /** A print has no RPL page and nothing filed on one; the second query is not run. */
    @Test
    fun `a draft RPL does not know has no filings`() {
        val print = drafts.insertDraft(
            DraftFromRegister(title = "o zmianie ustawy", initiator = DraftInitiator.GOVERNMENT, term = 10, startedOn = null),
        )
        filings.recordFiling(PROJECT, filed("778141", "Projekt ustawy.pdf", LocalDate.of(2026, 4, 9)))

        assertTrue(cards.cardFor(print).filings.isEmpty())
    }

    /** Filings belong to the draft whose project they were filed under and no other. */
    @Test
    fun `one draft's filings do not appear on another's card`() {
        val ours = governmentDraft()
        val theirs = governmentDraft(project = "12409052")
        filings.recordFiling(PROJECT, filed("778141", "Projekt ustawy.pdf", LocalDate.of(2026, 4, 9)))

        assertEquals(1, cards.cardFor(ours).filings.size)
        assertTrue(cards.cardFor(theirs).filings.isEmpty())
    }

    private fun governmentDraft(project: String = PROJECT): DraftId {
        val id = drafts.insertDraft(
            DraftFromRegister(title = "o podatku od nieruchomości", initiator = DraftInitiator.GOVERNMENT, term = 10, startedOn = null),
        )
        identifiers.claimForDraft(DraftIdentifierScheme.RCL_PROJECT, project, id)

        return id
    }

    private fun filed(documentId: String, fileName: String, createdOn: LocalDate?) = RclFiledDocument(
        documentId = documentId,
        catalogId = "13196867",
        fileName = fileName,
        href = "/docs/$documentId",
        author = "Ministerstwo Finansów",
        createdOn = createdOn,
    )

    private companion object {
        const val PROJECT = "12409051"
    }
}
