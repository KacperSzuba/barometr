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
 * What a draft's history says after the register has been read more than once.
 *
 * The register restates a process's whole history every time anything moves, so the
 * same stage is described repeatedly and the description changes: a first reading is
 * open while it is the last stage the register knows, and has an end once the
 * committee has the draft. Both statements are kept — that is what the transaction-time
 * axis is for — and exactly one of them is what the draft's history *is*.
 */
class StageTransitionRepositoryTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private val drafts = DraftRepository(dsl, clock)
    private val transitions = StageTransitionRepository(dsl, clock)

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(STAGE_TRANSITION).execute()
        dsl.deleteFrom(DRAFT_IDENTIFIER).execute()
        dsl.deleteFrom(DRAFT).execute()
    }

    /**
     * The failure this exists to stop: the same reading appearing twice on a card,
     * one of the two still claiming the draft never left it.
     */
    @Test
    fun `a stage a later reading closed is one stage, with the end it turned out to have`() {
        val draft = draft()
        readOnce(draft, fact(LegislativeStage.FIRST_READING, ARRIVED, until = null))
        readAgain(
            draft,
            fact(LegislativeStage.FIRST_READING, ARRIVED, until = MOVED_ON),
            fact(LegislativeStage.COMMITTEE_WORK, MOVED_ON, until = null),
        )

        val history = transitions.historyOf(draft)

        assertEquals(
            listOf(LegislativeStage.FIRST_READING, LegislativeStage.COMMITTEE_WORK),
            history.map { it.stage },
        )
        assertEquals(MOVED_ON, history.first().until, "the correction is what stands")
        // Both statements are still on record; which one is history is the question
        // being answered here, not whether the other was kept.
        assertEquals(3, dsl.fetchCount(STAGE_TRANSITION))
    }

    @Test
    fun `a stage the draft has not left is still open`() {
        val draft = draft()
        readOnce(draft, fact(LegislativeStage.COMMITTEE_WORK, ARRIVED, until = null))

        assertNull(transitions.historyOf(draft).single().until)
    }

    /**
     * Three stages on one day is a real process — second reading, back to committee,
     * third reading, all on 29 November 2023 — and the register's own ordinal is the
     * only ordering there is.
     */
    @Test
    fun `stages that share a day keep the register's order`() {
        val draft = draft()
        readOnce(
            draft,
            fact(LegislativeStage.SECOND_READING, ARRIVED, until = null, ordinal = 3),
            fact(LegislativeStage.COMMITTEE_WORK, ARRIVED, until = null, ordinal = 4),
            fact(LegislativeStage.THIRD_READING, ARRIVED, until = null, ordinal = 5),
        )

        assertEquals(
            listOf(
                LegislativeStage.SECOND_READING,
                LegislativeStage.COMMITTEE_WORK,
                LegislativeStage.THIRD_READING,
            ),
            transitions.historyOf(draft).map { it.stage },
        )
    }

    /**
     * A re-read that says nothing new adds nothing: the unique index on the fact is
     * what keeps a quarter-hourly poll from burying the two rows that changed.
     */
    @Test
    fun `restating the same facts records nothing`() {
        val draft = draft()
        readOnce(draft, fact(LegislativeStage.FIRST_READING, ARRIVED, until = MOVED_ON))

        val recorded = readAgain(draft, fact(LegislativeStage.FIRST_READING, ARRIVED, until = MOVED_ON))

        assertEquals(0, recorded)
        assertEquals(1, dsl.fetchCount(STAGE_TRANSITION))
    }

    // ——— Harness ————————————————————————————————————————————————————————————

    private fun readOnce(draft: DraftId, vararg facts: StageFact) =
        transitions.recordFacts(draft, facts.toList(), DocumentVersionId(Ids.next()), clock.instant())

    /** A later reading of the same process, believed later than the first. */
    private fun readAgain(draft: DraftId, vararg facts: StageFact): Int {
        clock.advanceBy(Duration.ofDays(30))

        return transitions.recordFacts(draft, facts.toList(), DocumentVersionId(Ids.next()), clock.instant())
    }

    private fun draft(): DraftId = drafts.insertDraft(
        DraftFromRegister(
            title = "Projekt ustawy ${Ids.next()}",
            initiator = DraftInitiator.GOVERNMENT,
            term = 10,
            startedOn = LocalDate.parse("2024-01-02"),
        ),
    )

    private fun fact(stage: LegislativeStage, from: Instant, until: Instant?, ordinal: Int = 0) = StageFact(
        stage = stage,
        from = from,
        until = until,
        ordinal = ordinal,
        sourceLabel = stage.wireName,
        isException = false,
    )

    private companion object {
        val ARRIVED: Instant = Instant.parse("2024-01-10T00:00:00Z")
        val MOVED_ON: Instant = Instant.parse("2024-02-10T00:00:00Z")
    }
}
