package pl.barometr.legislative.internal

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a register's list of dated stages becomes a history that can be queried by day.
 */
class StageTimelineTest {

    @Test
    fun `a stage runs until the next one starts`() {
        val timeline = StageTimeline.of(
            listOf(
                stage(0, "2023-11-20", LegislativeStage.REFERRED_TO_FIRST_READING),
                stage(1, "2023-11-28", LegislativeStage.FIRST_READING),
            ),
        )

        assertEquals(day("2023-11-20"), timeline[0].from)
        assertEquals(day("2023-11-28"), timeline[0].until)
        assertNull(timeline[1].until, "the draft is still at the last stage the register knows")
    }

    /**
     * The day this model has to be honest about. A second reading, a return to
     * committee and a third reading all fell on 29 November 2023 in one real process,
     * and the register dates stages without timing them — so each is recorded as
     * current for that day and the periods overlap, which the schema permits on
     * purpose. Saying the draft was at all three that day is true; picking one would
     * not be.
     */
    @Test
    fun `stages sharing a day each run to the end of it`() {
        val timeline = StageTimeline.of(
            listOf(
                stage(0, "2023-11-29", LegislativeStage.SECOND_READING),
                stage(1, "2023-11-29", LegislativeStage.COMMITTEE_WORK),
                stage(2, "2023-11-29", LegislativeStage.THIRD_READING),
                stage(3, "2023-12-08", LegislativeStage.SENATE_POSITION),
            ),
        )

        assertTrue(timeline.take(3).all { it.from == day("2023-11-29") })
        // The two that another stage displaces the same day run to the end of it; the
        // last one of the day runs on until the Senate takes it, which is where the
        // draft genuinely was.
        assertEquals(day("2023-11-30"), timeline[0].until)
        assertEquals(day("2023-11-30"), timeline[1].until)
        assertEquals(day("2023-12-08"), timeline[2].until)
        // Order within the day is the register's, which is the only ordering there is.
        assertEquals(listOf(0, 1, 2, 3), timeline.map { it.ordinal })
    }

    /**
     * A stage with no date cannot say what the status was on any day, and answering
     * that is the entire purpose of this table — the schema refuses a period with no
     * beginning outright.
     */
    @Test
    fun `a stage the register did not date is left out`() {
        val timeline = StageTimeline.of(
            listOf(
                stage(0, "2023-11-29", LegislativeStage.THIRD_READING),
                SejmProcessStage(ordinal = 1, date = null, stage = null, sourceLabel = "Uchwalono"),
            ),
        )

        assertEquals(1, timeline.size)
        assertEquals(LegislativeStage.THIRD_READING, timeline.single().stage)
    }

    @Test
    fun `going back to committee after a second reading is expected, not exceptional`() {
        val timeline = StageTimeline.of(
            listOf(
                stage(0, "2023-11-28", LegislativeStage.SECOND_READING),
                stage(1, "2023-11-29", LegislativeStage.COMMITTEE_WORK),
                stage(2, "2023-11-30", LegislativeStage.THIRD_READING),
            ),
        )

        assertTrue(timeline.none { it.isException }, "this is most Thursdays, not an anomaly")
    }

    @Test
    fun `a step the process does not make is recorded and flagged`() {
        val timeline = StageTimeline.of(
            listOf(
                stage(0, "2023-11-28", LegislativeStage.SUBMITTED_TO_SEJM),
                stage(1, "2023-11-29", LegislativeStage.PRESIDENT_SIGNED),
            ),
        )

        assertEquals(2, timeline.size, "an unexpected step is recorded, never refused")
        assertTrue(timeline[1].isException)
    }

    /**
     * The model has nothing to say about a stage it could not read, so it does not
     * call the step exceptional — that would report our own gap as the source's
     * irregularity.
     */
    @Test
    fun `a step into a stage this model cannot name is not called exceptional`() {
        val timeline = StageTimeline.of(
            listOf(
                stage(0, "2023-11-28", LegislativeStage.SUBMITTED_TO_SEJM),
                SejmProcessStage(1, LocalDate.parse("2023-11-29"), stage = null, sourceLabel = "Coś nowego"),
            ),
        )

        assertEquals(LegislativeStage.UNKNOWN, timeline[1].stage)
        assertEquals("Coś nowego", timeline[1].sourceLabel)
        assertTrue(timeline.none { it.isException })
    }

    @Test
    fun `stages arriving out of order are laid out by date`() {
        val timeline = StageTimeline.of(
            listOf(
                stage(0, "2023-12-08", LegislativeStage.SENATE_POSITION),
                stage(1, "2023-11-28", LegislativeStage.FIRST_READING),
            ),
        )

        assertEquals(listOf(LegislativeStage.FIRST_READING, LegislativeStage.SENATE_POSITION), timeline.map { it.stage })
    }

    private fun stage(ordinal: Int, date: String, stage: LegislativeStage) =
        SejmProcessStage(ordinal, LocalDate.parse(date), stage, sourceLabel = stage.wireName)

    private fun day(date: String): Instant = LocalDate.parse(date).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
}
