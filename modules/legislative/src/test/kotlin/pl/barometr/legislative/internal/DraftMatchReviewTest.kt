package pl.barometr.legislative.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_CONTINUATION
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_IDENTIFIER
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_MATCH_CANDIDATE
import pl.barometr.legislative.internal.jooq.tables.references.STAGE_TRANSITION
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * What a reviewer's decision about a join does, and what it refuses to do twice.
 */
class DraftMatchReviewTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private val drafts = DraftRepository(dsl, clock)
    private val identifiers = DraftIdentifierRepository(dsl, clock)
    private val continuations = DraftContinuationRepository(dsl, clock)
    private val candidates = DraftMatchCandidateRepository(dsl, clock)
    private val transitions = StageTransitionRepository(dsl, clock)
    private val review = DraftMatchReview(
        candidates,
        continuations,
        GovernmentProcessClosure(drafts, transitions, SimpleMeterRegistry(), clock),
    )

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(STAGE_TRANSITION).execute()
        dsl.deleteFrom(DRAFT_MATCH_CANDIDATE).execute()
        dsl.deleteFrom(DRAFT_CONTINUATION).execute()
        dsl.deleteFrom(DRAFT_IDENTIFIER).execute()
        dsl.deleteFrom(DRAFT).execute()
    }

    @Test
    fun `accepting joins the two registers, in the reviewer's name`() {
        val government = draft(CARD_TITLE, DraftIdentifierScheme.RCL_PROJECT)
        val print = draft(PRINT_TITLE, DraftIdentifierScheme.SEJM_PRINT)
        val queued = queuedJoin(government, print)

        review.acceptMatch(queued, reviewer = "operator@barometr.pl")

        val continuation = assertNotNull(dsl.selectFrom(DRAFT_CONTINUATION).fetchOne())
        assertEquals(government.value, continuation.governmentDraftId)
        assertEquals(print.value, continuation.sejmDraftId)
        // A person decided. Recording a similarity here would credit the machine with
        // a judgement it did not make.
        assertEquals(MatchMethod.MANUAL.wireName, continuation.joinedBy)
        assertEquals(null, continuation.confidence)

        val decided = assertNotNull(dsl.selectFrom(DRAFT_MATCH_CANDIDATE).fetchOne())
        assertEquals("accepted", decided.status)
        assertEquals("operator@barometr.pl", decided.reviewedBy)
    }

    @Test
    fun `rejecting records that somebody looked and joins nothing`() {
        val queued = queuedJoin(
            draft(CARD_TITLE, DraftIdentifierScheme.RCL_PROJECT),
            draft(PRINT_TITLE, DraftIdentifierScheme.SEJM_PRINT),
        )

        review.rejectMatch(queued, reviewer = "operator@barometr.pl")

        assertEquals(0, dsl.fetchCount(DRAFT_CONTINUATION))
        assertEquals("rejected", assertNotNull(dsl.selectFrom(DRAFT_MATCH_CANDIDATE).fetchOne()).status)
    }

    @Test
    fun `a decision already made cannot be made again`() {
        val queued = queuedJoin(
            draft(CARD_TITLE, DraftIdentifierScheme.RCL_PROJECT),
            draft(PRINT_TITLE, DraftIdentifierScheme.SEJM_PRINT),
        )
        review.acceptMatch(queued, reviewer = "first@barometr.pl")

        assertFailsWith<UnknownDraftMatchException> {
            review.rejectMatch(queued, reviewer = "second@barometr.pl")
        }

        assertEquals("accepted", assertNotNull(dsl.selectFrom(DRAFT_MATCH_CANDIDATE).fetchOne()).status)
    }

    @Test
    fun `a join that never existed is not found`() {
        assertFailsWith<UnknownDraftMatchException> {
            review.acceptMatch(UUID.randomUUID(), reviewer = "operator@barometr.pl")
        }
    }

    /**
     * The worst available outcome is the queue showing an item decided while nothing
     * about the two drafts changed, so the second pair is refused rather than dropped.
     */
    @Test
    fun `a draft already joined to another print refuses the second decision`() {
        val government = draft(CARD_TITLE, DraftIdentifierScheme.RCL_PROJECT)
        val first = draft(PRINT_TITLE, DraftIdentifierScheme.SEJM_PRINT)
        val second = draft(PRINT_TITLE, DraftIdentifierScheme.SEJM_PRINT)
        continuations.recordContinuation(government, first, MatchMethod.FUZZY, confidence = 0.9)
        val queued = queuedJoin(government, second)

        assertFailsWith<DraftAlreadyJoinedException> {
            review.acceptMatch(queued, reviewer = "operator@barometr.pl")
        }

        assertEquals(first.value, assertNotNull(dsl.selectFrom(DRAFT_CONTINUATION).fetchOne()).sejmDraftId)
    }

    /**
     * The matcher reached the same conclusion while the item waited. Agreeing with it
     * is not a failure, and leaving the item in the queue for ever would be.
     */
    @Test
    fun `accepting a pair the matcher already joined is agreement, not a conflict`() {
        val government = draft(CARD_TITLE, DraftIdentifierScheme.RCL_PROJECT)
        val print = draft(PRINT_TITLE, DraftIdentifierScheme.SEJM_PRINT)
        val queued = queuedJoin(government, print)
        continuations.recordContinuation(government, print, MatchMethod.FUZZY, confidence = 0.9)

        review.acceptMatch(queued, reviewer = "operator@barometr.pl")

        assertEquals("accepted", assertNotNull(dsl.selectFrom(DRAFT_MATCH_CANDIDATE).fetchOne()).status)
        assertEquals(1, dsl.fetchCount(DRAFT_CONTINUATION))
    }

    @Test
    fun `the queue carries both titles, because they are the decision`() {
        val government = draft(CARD_TITLE, DraftIdentifierScheme.RCL_PROJECT)
        val print = draft(PRINT_TITLE, DraftIdentifierScheme.SEJM_PRINT)
        queuedJoin(government, print)

        val waiting = review.awaitingReview(limit = 10).single()

        assertEquals(CARD_TITLE, waiting.governmentTitle)
        assertEquals(PRINT_TITLE, waiting.sejmTitle)
    }

    // ——— Harness ————————————————————————————————————————————————————————————

    private fun queuedJoin(government: DraftId, sejm: DraftId): UUID {
        candidates.queueForReview(government, sejm, confidence = 0.5)

        return candidates.awaitingReview(limit = 10)
            .first { it.governmentDraftId == government && it.sejmDraftId == sejm }
            .id
    }

    private fun draft(title: String, claimedBy: DraftIdentifierScheme): DraftId {
        val id = drafts.insertDraft(
            DraftFromRegister(
                title = title,
                initiator = DraftInitiator.GOVERNMENT,
                term = 10,
                startedOn = LocalDate.parse("2024-01-15"),
            ),
        )
        identifiers.claimForDraft(claimedBy, "${claimedBy.wireName}/${id.value}", id)

        return id
    }

    private companion object {
        const val CARD_TITLE = "Projekt ustawy o zmianie ustawy o odnawialnych źródłach energii"
        const val PRINT_TITLE = "Rządowy projekt ustawy o zmianie ustawy o odnawialnych źródłach energii"
    }
}
