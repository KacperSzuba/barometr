package pl.barometr.legislative.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.corpus.api.ArchivedDocument
import pl.barometr.corpus.api.DocumentCatalog
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.internal.jooq.tables.references.ACT
import pl.barometr.legislative.internal.jooq.tables.references.ACT_IDENTIFIER
import pl.barometr.legislative.internal.jooq.tables.references.ACT_MATCH_CANDIDATE
import pl.barometr.shared.ContentHash
import pl.barometr.shared.Eli
import pl.barometr.shared.Ids
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.SourceId
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Which of the three things happens to a print: pinned by the publisher, pinned by
 * title, handed to a person, or left alone.
 *
 * The thresholds are configuration, so the tests set them rather than depending on
 * where trigram similarity happens to land for one pair of Polish titles. What is
 * under test is the policy — which band a similarity falls into and what that causes
 * — not the number pg_trgm returns.
 */
class ActIdentityMatcherTest {

    private val dsl = PostgresTestDatabase.dsl()
    private val clock = TestClock()

    private val acts = ActRepository(dsl, clock)
    private val identifiers = ActIdentifierRepository(dsl, clock)
    private val candidates = ActMatchCandidateRepository(dsl, clock)

    private lateinit var meters: SimpleMeterRegistry

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(ACT_MATCH_CANDIDATE).execute()
        dsl.deleteFrom(ACT_IDENTIFIER).execute()
        dsl.deleteFrom(ACT).execute()
        meters = SimpleMeterRegistry()
    }

    @Test
    fun `a print the register already pinned is left exactly as it is`() {
        val actId = publishedAct(ENERGY_ACT_TITLE)
        identifiers.pointAtAct(IdentifierScheme.SEJM_PRINT, PRINT_ADDRESS, actId, MatchMethod.EXACT, 1.0)

        matcher(ENERGY_PRINT_TITLE, matchesAbove = 0.35).resolveDocumentToAct(printRecorded())

        val identifier = assertNotNull(
            dsl.selectFrom(ACT_IDENTIFIER)
                .where(ACT_IDENTIFIER.VALUE.eq(PRINT_ADDRESS))
                .fetchOne(),
        )
        // Still the publisher's own statement, not overwritten by a title comparison.
        assertEquals(MatchMethod.EXACT.wireName, identifier.resolvedBy)
        assertEquals(0, dsl.fetchCount(ACT_MATCH_CANDIDATE))
    }

    @Test
    fun `a title close enough to an act is pinned without asking anyone`() {
        val actId = publishedAct(ENERGY_ACT_TITLE)

        matcher(ENERGY_PRINT_TITLE, matchesAbove = 0.35).resolveDocumentToAct(printRecorded())

        val identifier = assertNotNull(
            dsl.selectFrom(ACT_IDENTIFIER)
                .where(ACT_IDENTIFIER.VALUE.eq(PRINT_ADDRESS))
                .fetchOne(),
        )
        assertEquals(actId.value, identifier.actId)
        assertEquals(MatchMethod.FUZZY.wireName, identifier.resolvedBy)
        // The similarity is kept, because a reviewer looking at this later needs to
        // know it was a guess and how good a one.
        assertNotNull(identifier.confidence)
        assertEquals(0, dsl.fetchCount(ACT_MATCH_CANDIDATE))
    }

    @Test
    fun `a plausible but uncertain title is handed to a person`() {
        val actId = publishedAct(ENERGY_ACT_TITLE)

        matcher(ENERGY_PRINT_TITLE, matchesAbove = 0.99).resolveDocumentToAct(printRecorded())

        val candidate = assertNotNull(dsl.selectFrom(ACT_MATCH_CANDIDATE).fetchOne())
        assertEquals(actId.value, candidate.actId)
        assertEquals("pending", candidate.status)
        assertEquals(PRINT_ADDRESS, candidate.value)
        assertEquals(0, dsl.fetchCount(ACT_IDENTIFIER), "nothing is pinned until somebody says so")
    }

    /**
     * The rule that keeps the queue usable. Most prints are for bills that have not
     * passed, so there is no act to find and no question worth asking: queueing them
     * would bury the real decisions.
     */
    @Test
    fun `a print with no act anywhere near it is left unpinned and unqueued`() {
        publishedAct(ENERGY_ACT_TITLE)

        matcher(UNRELATED_PRINT_TITLE, matchesAbove = 0.99).resolveDocumentToAct(printRecorded())

        assertEquals(0, dsl.fetchCount(ACT_MATCH_CANDIDATE))
        assertEquals(0, dsl.fetchCount(ACT_IDENTIFIER))
    }

    /**
     * A print cannot have become an act published before the print existed. Without
     * the guard the closest title is often the previous version of the same law, which
     * is the most convincing wrong answer available.
     */
    @Test
    fun `an act published before the print is not what the print became`() {
        publishedAct(ENERGY_ACT_TITLE, announcedOn = LocalDate.parse("2020-01-01"))

        matcher(ENERGY_PRINT_TITLE, matchesAbove = 0.35).resolveDocumentToAct(printRecorded())

        assertEquals(0, dsl.fetchCount(ACT_IDENTIFIER))
        assertEquals(0, dsl.fetchCount(ACT_MATCH_CANDIDATE))
    }

    @Test
    fun `documents that are not prints are none of this listener's business`() {
        publishedAct(ENERGY_ACT_TITLE)

        matcher(ENERGY_PRINT_TITLE, matchesAbove = 0.35).resolveDocumentToAct(
            printRecorded().copy(kind = DocumentKind("voting")),
        )

        assertEquals(0, dsl.fetchCount(ACT_IDENTIFIER))
    }

    @Test
    fun `a redelivered event does not queue the same decision twice`() {
        publishedAct(ENERGY_ACT_TITLE)
        val matcher = matcher(ENERGY_PRINT_TITLE, matchesAbove = 0.99)

        matcher.resolveDocumentToAct(printRecorded())
        matcher.resolveDocumentToAct(printRecorded())

        assertEquals(1, dsl.fetchCount(ACT_MATCH_CANDIDATE))
    }

    // ——— Harness ————————————————————————————————————————————————————————————

    /**
     * The print's title reaches the matcher through the corpus, not through the event,
     * so the fake carries it and the threshold is the other half of the scenario.
     */
    private fun matcher(printTitle: String, matchesAbove: Double) = ActIdentityMatcher(
        documents = FakeDocumentCatalog(printTitle),
        acts = acts,
        identifiers = identifiers,
        candidates = candidates,
        properties = LegislativeProperties(automaticMatchAbove = matchesAbove, reviewMatchAbove = 0.35),
        meters = meters,
    )

    private fun printRecorded() = DocumentVersionRecorded(
        documentId = DOCUMENT_ID,
        versionId = DocumentVersionId(Ids.next()),
        sourceId = SourceId(Ids.next()),
        connectorId = ConnectorId("sejm"),
        externalId = ExternalId(PRINT_ADDRESS),
        kind = DocumentKind("print"),
        contentHash = ContentHash.of(PRINT_ADDRESS.toByteArray()),
        versionNo = 1,
        occurredAt = clock.instant(),
    )

    private fun publishedAct(title: String, announcedOn: LocalDate = LocalDate.parse("2026-08-10")): ActId =
        acts.actFor(
            EliActMetadata(
                eli = Eli("DU/2026/1074"),
                title = title,
                type = "Ustawa",
                announcedOn = announcedOn,
                inForceFrom = null,
                prints = emptyList(),
                references = emptyList(),
                unmappedLabels = emptyList(),
            ),
        )

    private class FakeDocumentCatalog(private val title: String) : DocumentCatalog {
        override fun documentById(id: DocumentId) = ArchivedDocument(
            id = id,
            externalId = ExternalId(PRINT_ADDRESS),
            kind = DocumentKind("print"),
            title = title,
            publishedAt = PRINT_PUBLISHED_AT,
        )

        override fun countByKind() = emptyMap<DocumentKind, Int>()
    }

    private companion object {
        val DOCUMENT_ID = DocumentId(Ids.next())
        const val PRINT_ADDRESS = "term10/print/2620"
        val PRINT_PUBLISHED_AT: Instant = Instant.parse("2026-05-01T00:00:00Z")

        const val ENERGY_ACT_TITLE = "Ustawa z dnia 17 lipca 2026 r. o zmianie ustawy o cenach energii elektrycznej"
        const val ENERGY_PRINT_TITLE = "Rządowy projekt ustawy o zmianie ustawy o cenach energii elektrycznej"
        const val UNRELATED_PRINT_TITLE = "Sprawozdanie Komisji Zdrowia w sprawie powołania członka Rady Nadzorczej"
    }
}
