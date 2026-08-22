package pl.barometr.legislative.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.corpus.api.DocumentId
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.internal.jooq.tables.references.ACT
import pl.barometr.legislative.internal.jooq.tables.references.ACT_IDENTIFIER
import pl.barometr.legislative.internal.jooq.tables.references.ACT_MATCH_CANDIDATE
import pl.barometr.shared.Eli
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * What a reviewer's decision does, and what it refuses to do twice.
 */
class ActMatchReviewTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private val acts = ActRepository(dsl, clock)
    private val candidates = ActMatchCandidateRepository(dsl, clock)
    private val review = ActMatchReview(candidates, ActIdentifierRepository(dsl, clock))

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(ACT_MATCH_CANDIDATE).execute()
        dsl.deleteFrom(ACT_IDENTIFIER).execute()
        dsl.deleteFrom(ACT).execute()
    }

    @Test
    fun `accepting pins the document to the act, in the reviewer's name`() {
        val queued = queuedMatch(publishedAct())

        review.acceptMatch(queued, reviewer = "operator@barometr.pl")

        val identifier = assertNotNull(dsl.selectFrom(ACT_IDENTIFIER).fetchOne())
        assertEquals(PRINT_ADDRESS, identifier.value)
        // A person decided. Recording a similarity here would credit the machine with
        // a judgement it did not make.
        assertEquals(MatchMethod.MANUAL.wireName, identifier.resolvedBy)
        assertEquals(null, identifier.confidence)

        val decided = assertNotNull(dsl.selectFrom(ACT_MATCH_CANDIDATE).fetchOne())
        assertEquals("accepted", decided.status)
        assertEquals("operator@barometr.pl", decided.reviewedBy)
    }

    @Test
    fun `rejecting records that somebody looked and pins nothing`() {
        val queued = queuedMatch(publishedAct())

        review.rejectMatch(queued, reviewer = "operator@barometr.pl")

        assertEquals(0, dsl.fetchCount(ACT_IDENTIFIER))
        assertEquals("rejected", assertNotNull(dsl.selectFrom(ACT_MATCH_CANDIDATE).fetchOne()).status)
    }

    /**
     * Two reviewers open the same item; only one of them decides it. The second is
     * told there is nothing to decide rather than quietly overwriting the first.
     */
    @Test
    fun `a match already decided cannot be decided again`() {
        val queued = queuedMatch(publishedAct())
        review.acceptMatch(queued, reviewer = "first@barometr.pl")

        assertFailsWith<UnknownActMatchException> {
            review.rejectMatch(queued, reviewer = "second@barometr.pl")
        }

        assertEquals("accepted", assertNotNull(dsl.selectFrom(ACT_MATCH_CANDIDATE).fetchOne()).status)
    }

    @Test
    fun `a match that never existed is not found`() {
        assertFailsWith<UnknownActMatchException> {
            review.acceptMatch(UUID.randomUUID(), reviewer = "operator@barometr.pl")
        }
    }

    private fun publishedAct(): ActId = acts.actFor(
        EliActMetadata(
            eli = Eli("DU/2026/1074"),
            title = "Ustawa z dnia 17 lipca 2026 r. o zmianie ustawy o cenach energii",
            type = "Ustawa",
            announcedOn = LocalDate.parse("2026-08-10"),
            inForceFrom = null,
            prints = emptyList(),
            references = emptyList(),
            unmappedLabels = emptyList(),
        ),
    )

    private fun queuedMatch(actId: ActId): UUID {
        candidates.queueForReview(
            documentId = DocumentId(Ids.next()),
            actId = actId,
            scheme = IdentifierScheme.SEJM_PRINT,
            value = PRINT_ADDRESS,
            confidence = 0.42,
        )

        return candidates.awaitingReview(1).single().id
    }

    private companion object {
        const val PRINT_ADDRESS = "term10/print/2620"
    }
}
