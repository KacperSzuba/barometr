package pl.barometr.legislative.internal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a stage sits on the path, which is the first signal anything ranking a list
 * asks for.
 *
 * The number itself is arbitrary and is meant to be; what these pin is the shape — the
 * order, the two stages that are deliberately not on the scale, and the fact that no
 * draft ever reaches the top of it.
 */
class StageProgressTest {

    @Test
    fun `the further along the path, the higher the number`() {
        val path = listOf(
            LegislativeStage.PUBLIC_CONSULTATION,
            LegislativeStage.COUNCIL_OF_MINISTERS,
            LegislativeStage.FIRST_READING,
            LegislativeStage.THIRD_READING,
            LegislativeStage.SENATE_POSITION,
            LegislativeStage.PRESIDENT_SIGNED,
        )

        val scored = path.map(StageProgress::of)

        assertEquals(scored.sorted(), scored, "the path's order is the declaration order")
    }

    /**
     * A signature is not a publication. Leaving the top of the scale for an act keeps
     * the two apart, which is the same distinction the schema draws between a draft
     * and the thing it became.
     */
    @Test
    fun `no stage a draft can be at reaches the top of the scale`() {
        LegislativeStage.entries.forEach { stage ->
            assertTrue(StageProgress.of(stage) < 1.0, "$stage should leave room for an act")
        }
    }

    /**
     * `UNKNOWN` is last in the enum for want of anywhere better, and reading that
     * position as "nearly enacted" would rank this system's own blind spot above a
     * third reading.
     */
    @Test
    fun `a stage nobody could read places at the start, not the end`() {
        assertEquals(0.0, StageProgress.of(LegislativeStage.UNKNOWN))
        assertEquals(0.0, StageProgress.of(null))
        assertTrue(StageProgress.of(LegislativeStage.UNKNOWN) < StageProgress.of(LegislativeStage.FIRST_READING))
    }

    /**
     * A veto comes late in the declaration order because that is when it happens, and
     * it is the opposite of progress: the bill goes back to the Sejm to be voted on
     * again, which is exactly where it is pinned.
     */
    @Test
    fun `a veto is not progress`() {
        assertEquals(
            StageProgress.of(LegislativeStage.THIRD_READING),
            StageProgress.of(LegislativeStage.PRESIDENT_VETO),
        )
        assertTrue(
            StageProgress.of(LegislativeStage.PRESIDENT_VETO) <
                StageProgress.of(LegislativeStage.SENT_TO_PRESIDENT),
        )
    }

    @Test
    fun `every stage places somewhere between nothing and everything`() {
        LegislativeStage.entries.forEach { stage ->
            assertTrue(StageProgress.of(stage) in 0.0..1.0, "$stage placed off the scale")
        }
    }
}
