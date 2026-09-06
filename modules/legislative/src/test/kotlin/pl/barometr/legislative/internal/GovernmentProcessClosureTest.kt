package pl.barometr.legislative.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_CONTINUATION
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_IDENTIFIER
import pl.barometr.legislative.internal.jooq.tables.references.STAGE_TRANSITION
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * When the government's process ends, and when this refuses to say.
 *
 * An RPL card cannot state that a draft left — it leaves by arriving in the Sejm, and
 * that is the other register talking — so the period opened from a card stays open
 * until the two are joined. Everything here is about what that join is and is not
 * entitled to conclude.
 */
class GovernmentProcessClosureTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private val drafts = DraftRepository(dsl, clock)
    private val transitions = StageTransitionRepository(dsl, clock)
    private val paces = StagePaceRepository(dsl)

    private lateinit var closure: GovernmentProcessClosure

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(STAGE_TRANSITION).execute()
        dsl.deleteFrom(DRAFT_CONTINUATION).execute()
        dsl.deleteFrom(DRAFT_IDENTIFIER).execute()
        dsl.deleteFrom(DRAFT).execute()
        closure = GovernmentProcessClosure(drafts, transitions, SimpleMeterRegistry(), clock)
    }

    @Test
    fun `the process ends on the day the print arrived`() {
        val government = draft(startedOn = FILED)
        val print = draft(startedOn = PRINTED)
        inTheGovernmentProcessSince(government, FILED)
        statedBySejm(print, PRINTED)

        // A later reading, as it is in life: the card was read when it was archived
        // and the join is made when the Sejm's register catches up.
        clock.advanceBy(Duration.ofDays(1))
        closure.closeOnArrivalInSejm(government, print)

        val process = transitions.historyOf(government).single()
        assertEquals(FILED.atStartOfDay(ZoneOffset.UTC).toInstant(), process.since)
        assertEquals(PRINTED.atStartOfDay(ZoneOffset.UTC).toInstant(), process.until)
        // The correction is a fact beside the one it corrects, not an update of it.
        assertEquals(2, dsl.fetchCount(STAGE_TRANSITION, STAGE_TRANSITION.DRAFT_ID.eq(government.value)))
    }

    /**
     * The point of closing it at all: a stage nothing ever ended is a stage whose
     * length cannot be measured, and every estimate this system shows is a median of
     * completed stays.
     */
    @Test
    fun `a closed process is a stay the archive can measure`() {
        repeat(5) {
            val government = draft(startedOn = FILED)
            val print = draft(startedOn = PRINTED)
            inTheGovernmentProcessSince(government, FILED)
            statedBySejm(print, PRINTED)
            closure.closeOnArrivalInSejm(government, print)
        }

        assertEquals(
            Duration.between(FILED.atStartOfDay(ZoneOffset.UTC), PRINTED.atStartOfDay(ZoneOffset.UTC)),
            paces.measure().medianFor(DraftInitiator.GOVERNMENT, LegislativeStage.GOVERNMENT_PROCESS),
        )
    }

    /** Nothing to correct: the draft never reached the Sejm as far as this knows. */
    @Test
    fun `a print whose register never dated its start leaves the process open`() {
        val government = draft(startedOn = FILED)
        val print = draft(startedOn = null)
        inTheGovernmentProcessSince(government, FILED)
        statedBySejm(print, PRINTED)

        closure.closeOnArrivalInSejm(government, print)

        assertNull(transitions.historyOf(government).single().until)
    }

    /**
     * One of the two registers is wrong, and which is not answerable from here. An
     * empty period is also the one thing the schema refuses outright.
     */
    @Test
    fun `a print that predates the card it came from closes nothing`() {
        val government = draft(startedOn = PRINTED)
        val print = draft(startedOn = FILED)
        inTheGovernmentProcessSince(government, PRINTED)
        statedBySejm(print, FILED)

        closure.closeOnArrivalInSejm(government, print)

        assertNull(transitions.historyOf(government).single().until)
    }

    /**
     * A correction with nothing behind it is worse than the open period it replaces:
     * every other fact in this table names the document version that stated it.
     */
    @Test
    fun `a print with no dated stage of its own states nothing about the card`() {
        val government = draft(startedOn = FILED)
        val print = draft(startedOn = PRINTED)
        inTheGovernmentProcessSince(government, FILED)

        closure.closeOnArrivalInSejm(government, print)

        assertNull(transitions.historyOf(government).single().until)
    }

    @Test
    fun `closing an already closed process records nothing further`() {
        val government = draft(startedOn = FILED)
        val print = draft(startedOn = PRINTED)
        inTheGovernmentProcessSince(government, FILED)
        statedBySejm(print, PRINTED)

        closure.closeOnArrivalInSejm(government, print)
        closure.closeOnArrivalInSejm(government, print)

        assertEquals(2, dsl.fetchCount(STAGE_TRANSITION, STAGE_TRANSITION.DRAFT_ID.eq(government.value)))
    }

    /**
     * The finer stages are the change register's own statements about moves inside the
     * process. Ending them from the Sejm's dates would be one source correcting
     * another's facts rather than adding its own.
     */
    @Test
    fun `the stages a change register dated are left exactly as they are`() {
        val government = draft(startedOn = FILED)
        val print = draft(startedOn = PRINTED)
        inTheGovernmentProcessSince(government, FILED)
        inConsultationSince(government, FILED.plusDays(10))
        statedBySejm(print, PRINTED)

        closure.closeOnArrivalInSejm(government, print)

        assertNull(
            transitions.historyOf(government)
                .single { it.stage == LegislativeStage.PUBLIC_CONSULTATION }
                .until,
        )
    }

    // ——— Harness ————————————————————————————————————————————————————————————

    private fun inTheGovernmentProcessSince(draft: DraftId, day: LocalDate) =
        record(draft, LegislativeStage.GOVERNMENT_PROCESS, day, "Konsultacje publiczne")

    private fun inConsultationSince(draft: DraftId, day: LocalDate) =
        record(draft, LegislativeStage.PUBLIC_CONSULTATION, day, "Konsultacje publiczne")

    /** What the Sejm's own process document says about the draft arriving there. */
    private fun statedBySejm(draft: DraftId, day: LocalDate) =
        record(draft, LegislativeStage.SUBMITTED_TO_SEJM, day, "Wpłynął do Sejmu")

    private fun record(draft: DraftId, stage: LegislativeStage, day: LocalDate, label: String) =
        transitions.recordFacts(
            draftId = draft,
            facts = listOf(
                StageFact(
                    stage = stage,
                    from = day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    until = null,
                    ordinal = 0,
                    sourceLabel = label,
                    isException = false,
                ),
            ),
            statedBy = DocumentVersionId(Ids.next()),
            knownAt = clock.instant(),
        )

    private fun draft(startedOn: LocalDate?): DraftId = drafts.insertDraft(
        DraftFromRegister(
            title = "Projekt ustawy ${Ids.next()}",
            initiator = DraftInitiator.GOVERNMENT,
            term = 10,
            startedOn = startedOn,
        ),
    )

    private companion object {
        /** The day RPL created the draft, and the day the Sejm printed it. */
        val FILED: LocalDate = LocalDate.parse("2024-01-15")
        val PRINTED: LocalDate = LocalDate.parse("2024-06-01")
    }
}
