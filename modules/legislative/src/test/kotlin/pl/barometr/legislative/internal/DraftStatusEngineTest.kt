package pl.barometr.legislative.internal

import org.junit.jupiter.api.Test
import pl.barometr.legislative.api.DraftId
import pl.barometr.shared.Ids
import pl.barometr.testing.TestClock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The three questions a card answers, and the one distinction it must never blur.
 */
class DraftStatusEngineTest {

    private val clock = TestClock(Instant.parse("2024-03-01T00:00:00Z"))
    private val engine = DraftStatusEngine(clock)

    @Test
    fun `where the draft is, is the latest stage the register put it at`() {
        val status = assertNotNull(engine.statusOf(draft(), history(), paces()))

        assertEquals(LegislativeStage.SECOND_READING, status.currentStage)
        assertEquals(Instant.parse("2024-02-01T00:00:00Z"), status.since)
        assertEquals("II czytanie na posiedzeniu Sejmu", status.sourceLabel)
    }

    /**
     * The step the process aims at, not the likeliest detour. After a second reading a
     * bill often returns to committee first, but where it is going is the third
     * reading, and naming the detour would be precise about the wrong thing.
     */
    @Test
    fun `what happens next comes from the process model`() {
        val status = assertNotNull(engine.statusOf(draft(), history(), paces()))

        assertEquals(LegislativeStage.THIRD_READING, status.expectedNext)
    }

    @Test
    fun `when it happens is the median stay added to the day it arrived`() {
        val status = assertNotNull(
            engine.statusOf(draft(), history(), paces(median = Duration.ofDays(14))),
        )

        assertEquals(Instant.parse("2024-02-15T00:00:00Z"), status.expectedNextBy)
    }

    /**
     * With too few completed stays there is no median, and no estimate is a better
     * answer than one measured from three examples. The card says which it has.
     */
    @Test
    fun `an unmeasurable stage gets no estimate rather than an invented one`() {
        val thin = StagePaces(
            listOf(StagePace(LegislativeStage.SECOND_READING, null, Duration.ofDays(14), observations = 2)),
        )

        val status = assertNotNull(engine.statusOf(draft(), history(), thin))

        assertEquals(LegislativeStage.THIRD_READING, status.expectedNext)
        assertNull(status.expectedNextBy)
        assertNull(status.stalledSince, "with nothing to be twice of, nothing is overdue")
    }

    /**
     * The distinction the specification is emphatic about: a date computed from the
     * journal and a date guessed from medians are different claims, and they leave this
     * engine in different fields.
     */
    @Test
    fun `a date fixed by the journal is kept apart from the estimate`() {
        val published = draft(inForceFrom = LocalDate.parse("2024-07-01"))

        val status = assertNotNull(engine.statusOf(published, history(), paces()))

        val deadline = assertNotNull(status.hardDeadline)
        assertEquals(Instant.parse("2024-07-01T00:00:00Z"), deadline.on)
        assertEquals(HardDeadlineKind.ENTRY_INTO_FORCE, deadline.kind)
        assertNotNull(status.expectedNextBy, "the estimate is still there, in its own field")
    }

    @Test
    fun `a draft that has sat for more than twice the usual stay is stalled`() {
        val status = assertNotNull(
            engine.statusOf(draft(), history(), paces(median = Duration.ofDays(7))),
        )

        // Arrived 1 February, a fortnight is twice the usual stay, and it is March.
        assertEquals(Instant.parse("2024-02-15T00:00:00Z"), status.stalledSince)
    }

    @Test
    fun `a draft still inside the usual stay is not stalled`() {
        val status = assertNotNull(
            engine.statusOf(draft(), history(), paces(median = Duration.ofDays(60))),
        )

        assertNull(status.stalledSince)
    }

    /**
     * A finished bill is not stuck, it is finished — and reporting one enacted last
     * winter as stalled would make the flag worth nothing.
     */
    @Test
    fun `a closed draft is never stalled and expects nothing next`() {
        val closed = draft(closedOn = LocalDate.parse("2024-02-05"), outcome = DraftOutcome.ENACTED)

        val status = assertNotNull(engine.statusOf(closed, history(), paces(median = Duration.ofDays(1))))

        assertNull(status.stalledSince)
        assertNull(status.expectedNext)
        assertNull(status.expectedNextBy)
    }

    /**
     * The failure this stops: a bill the Sejm has been reading for months, reported as
     * out to public comment and — once the archive can measure that stage — stuck
     * there. RPL never says a draft left; the coarse period ending is the only place
     * that is said, and it outranks every finer stage the change register dated.
     */
    @Test
    fun `a government draft that reached the Sejm stands where it left, not where it last was`() {
        val status = assertNotNull(engine.statusOf(draft(), leftForTheSejm(), governmentPaces()))

        assertEquals(LegislativeStage.GOVERNMENT_PROCESS, status.currentStage)
        assertEquals(Instant.parse("2024-02-15T00:00:00Z"), status.until)
    }

    @Test
    fun `a draft that left the register it is described in is not stalled in it`() {
        val status = assertNotNull(engine.statusOf(draft(), leftForTheSejm(), governmentPaces()))

        assertNull(status.stalledSince, "it did not get stuck, it moved to another register")
        assertNull(status.expectedNext, "where it goes next is the Sejm's register to say")
        assertNull(status.expectedNextBy)
    }

    /**
     * The other half of the same rule: a draft the government still has is described by
     * this register, and sitting in consultation for twice the usual stay is exactly
     * what a reader wants flagged.
     */
    @Test
    fun `a government draft still in the process is stalled like any other`() {
        val stillThere = listOf(
            recorded(LegislativeStage.GOVERNMENT_PROCESS, "2024-01-02", ordinal = 0),
            recorded(LegislativeStage.PUBLIC_CONSULTATION, "2024-01-10", ordinal = 1),
        )

        val status = assertNotNull(engine.statusOf(draft(), stillThere, governmentPaces()))

        assertEquals(LegislativeStage.PUBLIC_CONSULTATION, status.currentStage)
        assertNull(status.until)
        assertEquals(Instant.parse("2024-01-24T00:00:00Z"), status.stalledSince)
    }

    @Test
    fun `a draft nothing is recorded about has no status at all`() {
        assertNull(engine.statusOf(draft(), history = emptyList(), paces = paces()))
    }

    /**
     * On a day that held three stages the register's order is the only order there is,
     * so the last of them is where the draft ended up.
     */
    @Test
    fun `the last stage of a crowded day is the current one`() {
        val oneDay = listOf(
            recorded(LegislativeStage.SECOND_READING, "2024-02-01", ordinal = 4),
            recorded(LegislativeStage.COMMITTEE_WORK, "2024-02-01", ordinal = 5),
            recorded(LegislativeStage.THIRD_READING, "2024-02-01", ordinal = 6),
        )

        val status = assertNotNull(engine.statusOf(draft(), oneDay, paces()))

        assertEquals(LegislativeStage.THIRD_READING, status.currentStage)
    }

    // ——— Harness ————————————————————————————————————————————————————————————

    private fun draft(
        closedOn: LocalDate? = null,
        outcome: DraftOutcome? = null,
        inForceFrom: LocalDate? = null,
    ) = DraftSummary(
        id = DraftId(Ids.next()),
        title = "Rządowy projekt ustawy o zmianie ustawy o cenach energii",
        initiator = DraftInitiator.GOVERNMENT,
        term = 10,
        startedOn = LocalDate.parse("2024-01-02"),
        closedOn = closedOn,
        outcome = outcome,
        inForceFrom = inForceFrom,
    )

    /**
     * A government draft as both RPL sources leave it: the coarse process ended on the
     * day the Sejm printed it, and a consultation the change register dated and never
     * closed, because that register never closes anything.
     */
    private fun leftForTheSejm() = listOf(
        recorded(LegislativeStage.GOVERNMENT_PROCESS, "2024-01-02", ordinal = 0, until = "2024-02-15"),
        recorded(LegislativeStage.PUBLIC_CONSULTATION, "2024-01-10", ordinal = 1),
    )

    private fun history() = listOf(
        recorded(LegislativeStage.FIRST_READING, "2024-01-10", ordinal = 2, until = "2024-02-01"),
        recorded(LegislativeStage.SECOND_READING, "2024-02-01", ordinal = 3),
    )

    private fun recorded(
        stage: LegislativeStage,
        since: String,
        ordinal: Int,
        until: String? = null,
    ) = RecordedStage(
        stage = stage,
        since = Instant.parse("${since}T00:00:00Z"),
        until = until?.let { Instant.parse("${it}T00:00:00Z") },
        ordinal = ordinal,
        sourceLabel = when (stage) {
            LegislativeStage.SECOND_READING -> "II czytanie na posiedzeniu Sejmu"
            else -> stage.wireName
        },
        isException = false,
    )

    /** Medians for the government's own stages, which exist once such stays close. */
    private fun governmentPaces(median: Duration = Duration.ofDays(7)) = StagePaces(
        listOf(
            StagePace(LegislativeStage.GOVERNMENT_PROCESS, DraftInitiator.GOVERNMENT, median, observations = 40),
            StagePace(LegislativeStage.PUBLIC_CONSULTATION, DraftInitiator.GOVERNMENT, median, observations = 40),
        ),
    )

    private fun paces(median: Duration = Duration.ofDays(30)) = StagePaces(
        listOf(
            StagePace(LegislativeStage.SECOND_READING, DraftInitiator.GOVERNMENT, median, observations = 40),
        ),
    )
}
