package pl.barometr.legislative.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_IDENTIFIER
import pl.barometr.legislative.internal.jooq.tables.references.STAGE_TRANSITION
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How long a stage takes, measured out of the archive rather than assumed — the
 * number every estimate on a card rests on.
 */
class StagePaceRepositoryTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private val drafts = DraftRepository(dsl, clock)
    private val transitions = StageTransitionRepository(dsl, clock)
    private val paces = StagePaceRepository(dsl)

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(STAGE_TRANSITION).execute()
        dsl.deleteFrom(DRAFT_IDENTIFIER).execute()
        dsl.deleteFrom(DRAFT).execute()
    }

    @Test
    fun `the median stay is measured per initiator once there are enough of them`() {
        // Five government bills spending 2, 4, 6, 8 and 10 days in committee.
        listOf(2L, 4L, 6L, 8L, 10L).forEach { days ->
            stayInCommittee(DraftInitiator.GOVERNMENT, days)
        }

        val measured = paces.measure()

        assertEquals(
            Duration.ofDays(6),
            measured.medianFor(DraftInitiator.GOVERNMENT, LegislativeStage.COMMITTEE_WORK),
        )
    }

    /**
     * Fewer completed stays than the threshold and the median is an anecdote, so the
     * question goes unanswered rather than answered from three examples.
     */
    @Test
    fun `too few stays leaves the stage unmeasured`() {
        listOf(2L, 40L).forEach { days -> stayInCommittee(DraftInitiator.CITIZENS, days) }

        assertNull(paces.measure().medianFor(DraftInitiator.CITIZENS, LegislativeStage.COMMITTEE_WORK))
    }

    /**
     * A stage a draft has not left says nothing about how long the stage takes.
     * Counting it would report the archive's youngest bills as its quickest.
     */
    @Test
    fun `a stage still current is not counted as a stay`() {
        repeat(5) { stayInCommittee(DraftInitiator.GOVERNMENT, days = 4) }
        val stillThere = draft(DraftInitiator.GOVERNMENT)
        transitions.recordFacts(
            draftId = stillThere,
            facts = listOf(fact(LegislativeStage.COMMITTEE_WORK, ARRIVED, until = null)),
            statedBy = DocumentVersionId(Ids.next()),
            knownAt = clock.instant(),
        )

        assertEquals(
            Duration.ofDays(4),
            paces.measure().medianFor(DraftInitiator.GOVERNMENT, LegislativeStage.COMMITTEE_WORK),
        )
    }

    /**
     * A citizens' bill and a government bill do not move at the same speed, but with
     * too few of the first the figure across all initiators is a better answer than
     * none.
     */
    @Test
    fun `a rare initiator falls back to the figure across all of them`() {
        repeat(5) { stayInCommittee(DraftInitiator.GOVERNMENT, days = 30) }
        stayInCommittee(DraftInitiator.CITIZENS, days = 2)

        val measured = paces.measure()

        assertEquals(
            Duration.ofDays(30),
            measured.medianFor(DraftInitiator.CITIZENS, LegislativeStage.COMMITTEE_WORK),
            "six stays overall, five of them at thirty days",
        )
    }

    private fun stayInCommittee(initiator: DraftInitiator, days: Long) {
        transitions.recordFacts(
            draftId = draft(initiator),
            facts = listOf(fact(LegislativeStage.COMMITTEE_WORK, ARRIVED, ARRIVED.plus(Duration.ofDays(days)))),
            statedBy = DocumentVersionId(Ids.next()),
            knownAt = clock.instant(),
        )
    }

    private fun draft(initiator: DraftInitiator): DraftId = drafts.insertDraft(
        DraftFromRegister(
            title = "Projekt ustawy ${Ids.next()}",
            initiator = initiator,
            term = 10,
            startedOn = LocalDate.parse("2024-01-02"),
        ),
    )

    private fun fact(stage: LegislativeStage, from: Instant, until: Instant?) = StageFact(
        stage = stage,
        from = from,
        until = until,
        ordinal = 0,
        sourceLabel = stage.wireName,
        isException = false,
    )

    private companion object {
        val ARRIVED: Instant = Instant.parse("2024-01-10T00:00:00Z")
    }
}
