package pl.barometr.alerts.internal

import org.springframework.stereotype.Component
import pl.barometr.legislative.api.LegislativeSignals
import pl.barometr.profiles.api.InterestKind
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * The order things are read in.
 *
 * **Linear, with the weights written down.** The specification asks for explainable
 * over accurate and gives the reason: a reader who cannot see why something is at the
 * top has no way to disagree with it, and a ranking nobody can disagree with is one
 * nobody trusts. Every number below is arguable, which is the point — they are meant
 * to be argued with and recalibrated against how the top ten actually reads.
 *
 * Three signals, because three are the ones this system can honestly answer today.
 *
 * **How far along it is**, which the specification names first: a bill at its third
 * reading will be law in weeks and one in inter-ministerial agreement may never be.
 * The position comes from legislative, which owns the path.
 *
 * **How directly it was chosen.** Somebody who put an act on a watchlist by name has
 * said something much stronger than somebody whose keyword happened to appear in a
 * title, and the difference is most of what separates a useful list from a feed.
 *
 * **Whether a date is coming.** Only a date somebody else fixed counts; an estimate
 * out of historical medians is a guess, and letting a guess raise something up a list
 * would be this system quietly treating its own arithmetic as a statutory deadline.
 *
 * What is deliberately *not* here: the size of a change between versions, the number
 * of sources carrying it, and how fast coverage is growing. All three are in the
 * specification and none can be computed yet — a diff, a cluster and a media corpus
 * respectively — and a weight on a signal that is always zero is a weight that quietly
 * rescales everything else.
 *
 * Nor is a stalled draft scored, in either direction. It is a fact the status engine
 * already reports and a reader can already see; whether being stuck makes a matter
 * more urgent or less is a question this has no evidence to answer, and inventing a
 * sign for it would put a number on a coin flip.
 */
@Component
class SignificanceScale(private val clock: Clock) {

    /**
     * [signals] is null for a draft nothing is recorded about, which scores as the
     * start of the path rather than as an error — the item still matched somebody.
     */
    fun weigh(signals: LegislativeSignals?, matchedBy: InterestKind): Significance {
        val progress = signals?.progress ?: 0.0
        val urgency = deadlineWeight(signals?.hardDeadlineOn)

        val score = PROGRESS_WEIGHT * progress +
            DIRECTNESS_WEIGHT * directnessOf(matchedBy) +
            DEADLINE_WEIGHT * urgency

        return Significance(
            score = (score * Significance.MAXIMUM).roundToInt().coerceIn(0, Significance.MAXIMUM),
            reasons = reasonsFor(progress, signals?.hardDeadlineOn),
        )
    }

    /**
     * How much choosing this said. An act on a watchlist is a sentence about this act;
     * an industry code is a sentence about a thousand of them.
     */
    private fun directnessOf(kind: InterestKind): Double = when (kind) {
        InterestKind.ACT, InterestKind.DRAFT -> 1.0
        InterestKind.PKD -> 0.6
        InterestKind.REGION -> 0.5
        InterestKind.KEYWORD -> 0.3
    }

    /**
     * Nearer is heavier, and a date already past weighs nothing: a vacatio legis that
     * expired last spring is history, and history does not need to reach the top of
     * anybody's list.
     */
    private fun deadlineWeight(on: Instant?): Double {
        val remaining = Duration.between(clock.instant(), on ?: return 0.0)

        return when {
            remaining.isNegative -> 0.0
            remaining <= IMMINENT -> 1.0
            remaining <= APPROACHING -> 0.6
            remaining <= DISTANT -> 0.3
            else -> 0.0
        }
    }

    private fun reasonsFor(progress: Double, deadline: Instant?): List<SignificanceReason> = buildList {
        when {
            progress >= IN_FORCE_AT -> add(SignificanceReason.IN_FORCE)
            progress >= NEARING_ENACTMENT_AT -> add(SignificanceReason.NEARING_ENACTMENT)
        }

        val remaining = deadline?.let { Duration.between(clock.instant(), it) }
        when {
            remaining == null || remaining.isNegative -> Unit
            remaining <= IMMINENT -> add(SignificanceReason.DEADLINE_IMMINENT)
            remaining <= APPROACHING -> add(SignificanceReason.DEADLINE_APPROACHING)
            remaining <= DISTANT -> add(SignificanceReason.DEADLINE_AHEAD)
        }
    }

    private companion object {
        // Sum to one, so a score is a fraction of a hundred rather than an accident of
        // arithmetic. Changing one means changing another.
        const val PROGRESS_WEIGHT = 0.45
        const val DIRECTNESS_WEIGHT = 0.35
        const val DEADLINE_WEIGHT = 0.20

        /** Only a published act reaches the end of the path. */
        const val IN_FORCE_AT = 1.0

        /** From the Senate onwards, roughly: the stretch where a bill usually becomes law. */
        const val NEARING_ENACTMENT_AT = 0.7

        val IMMINENT: Duration = Duration.ofDays(7)
        val APPROACHING: Duration = Duration.ofDays(30)
        val DISTANT: Duration = Duration.ofDays(90)
    }
}
