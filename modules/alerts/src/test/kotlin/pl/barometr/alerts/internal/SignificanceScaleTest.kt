package pl.barometr.alerts.internal

import org.junit.jupiter.api.Test
import pl.barometr.legislative.api.LegislativeSignals
import pl.barometr.profiles.api.InterestKind
import pl.barometr.testing.TestClock
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The order things are read in.
 *
 * Most of what follows asserts relations rather than numbers, on purpose: the weights
 * are meant to be argued with and recalibrated, and a suite that froze them would make
 * every recalibration look like a regression. What must not change without somebody
 * deciding it are the directions — later beats earlier, chosen beats caught, soon beats
 * eventually — and that every contribution to a score can be explained.
 */
class SignificanceScaleTest {

    private val clock = TestClock()
    private val scale = SignificanceScale(clock)

    @Test
    fun `the same thing is worth more the further along it is`() {
        val early = scale.weigh(signals(progress = 0.1), InterestKind.PKD)
        val late = scale.weigh(signals(progress = 0.9), InterestKind.PKD)

        assertTrue(late.score > early.score)
    }

    /**
     * Somebody who put an act on a watchlist by name has said something much stronger
     * than somebody whose keyword happened to appear in a title, and the difference is
     * most of what separates a useful list from a feed.
     */
    @Test
    fun `something chosen by name outranks something caught by a rule of thumb`() {
        val watched = scale.weigh(signals(progress = 0.5), InterestKind.ACT)
        val bySector = scale.weigh(signals(progress = 0.5), InterestKind.PKD)
        val byWord = scale.weigh(signals(progress = 0.5), InterestKind.KEYWORD)

        assertTrue(watched.score > bySector.score)
        assertTrue(bySector.score > byWord.score)
    }

    @Test
    fun `a date coming sooner is worth more than one further out`() {
        val week = scale.weigh(signals(deadlineIn = Duration.ofDays(3)), InterestKind.PKD)
        val month = scale.weigh(signals(deadlineIn = Duration.ofDays(20)), InterestKind.PKD)
        val quarter = scale.weigh(signals(deadlineIn = Duration.ofDays(60)), InterestKind.PKD)
        val none = scale.weigh(signals(), InterestKind.PKD)

        assertTrue(week.score > month.score)
        assertTrue(month.score > quarter.score)
        assertTrue(quarter.score > none.score)
    }

    /**
     * A vacatio legis that expired last spring is history, and history does not need to
     * reach the top of anybody's list.
     */
    @Test
    fun `a date already past weighs nothing`() {
        val gone = scale.weigh(signals(deadlineIn = Duration.ofDays(-30)), InterestKind.PKD)

        assertEquals(scale.weigh(signals(), InterestKind.PKD).score, gone.score)
        assertTrue(gone.reasons.isEmpty())
    }

    /** Everything at once, which is the only case that reaches the top. */
    @Test
    fun `the scale tops out at a hundred and cannot exceed it`() {
        val most = scale.weigh(
            signals(progress = 1.0, deadlineIn = Duration.ofDays(1)),
            InterestKind.ACT,
        )

        assertEquals(Significance.MAXIMUM, most.score)
    }

    @Test
    fun `nothing known about a draft still scores what the match is worth`() {
        val unplaceable = scale.weigh(signals = null, matchedBy = InterestKind.ACT)

        assertTrue(unplaceable.score > 0, "it still matched somebody by name")
        assertTrue(unplaceable.reasons.isEmpty(), "and there is nothing to say about where it is")
    }

    // ——— Why ————————————————————————————————————————————————————————————————

    @Test
    fun `a published act says so, and a bill near the end says that instead`() {
        assertContains(scale.weigh(signals(progress = 1.0), InterestKind.ACT).reasons, SignificanceReason.IN_FORCE)

        val nearlyLaw = scale.weigh(signals(progress = 0.8), InterestKind.ACT).reasons
        assertContains(nearlyLaw, SignificanceReason.NEARING_ENACTMENT)
        assertTrue(SignificanceReason.IN_FORCE !in nearlyLaw)
    }

    @Test
    fun `a draft early in the process is ranked low and says nothing about why`() {
        val early = scale.weigh(signals(progress = 0.2), InterestKind.KEYWORD)

        assertTrue(early.reasons.isEmpty())
    }

    /**
     * Every weight that moved the score has a reason beside it. An explanation missing
     * one of its terms is worse than none: it invites the reader to trust a number
     * whose parts do not add up to what they were shown.
     */
    @Test
    fun `every deadline that counts towards a score is named`() {
        listOf(
            Duration.ofDays(3) to SignificanceReason.DEADLINE_IMMINENT,
            Duration.ofDays(20) to SignificanceReason.DEADLINE_APPROACHING,
            Duration.ofDays(60) to SignificanceReason.DEADLINE_AHEAD,
        ).forEach { (away, expected) ->
            val weighed = scale.weigh(signals(deadlineIn = away), InterestKind.PKD)

            assertTrue(weighed.score > scale.weigh(signals(), InterestKind.PKD).score)
            assertContains(weighed.reasons, expected, "a date $away away counted but went unexplained")
        }
    }

    @Test
    fun `a date so far off that it counts for nothing is not named either`() {
        val distant = scale.weigh(signals(deadlineIn = Duration.ofDays(400)), InterestKind.PKD)

        assertEquals(scale.weigh(signals(), InterestKind.PKD).score, distant.score)
        assertTrue(distant.reasons.isEmpty())
    }

    private fun signals(progress: Double = 0.0, deadlineIn: Duration? = null): LegislativeSignals =
        LegislativeSignals(progress, deadlineIn?.let { clock.instant().plus(it) })
}
