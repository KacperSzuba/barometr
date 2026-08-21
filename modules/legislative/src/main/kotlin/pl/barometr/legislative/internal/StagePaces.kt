package pl.barometr.legislative.internal

import java.time.Duration

/**
 * What the archive says about how long each stage takes.
 *
 * A government bill and a citizens' bill do not move at the same speed, so the
 * figures are kept per initiator — but only where there are enough completed stays to
 * mean anything. Below that, the answer is the figure across all initiators, and
 * below *that* there is no answer, which is a better one than a number invented from
 * three examples.
 */
class StagePaces(paces: List<StagePace>) {

    private val byInitiator = paces.filter { it.initiator != null }
        .associateBy { it.initiator to it.stage }

    private val overall = paces.filter { it.initiator == null }.associateBy { it.stage }

    fun medianFor(initiator: DraftInitiator, stage: LegislativeStage): Duration? =
        byInitiator[initiator to stage]?.takeIf { it.observations >= ENOUGH }?.median
            ?: overall[stage]?.takeIf { it.observations >= ENOUGH }?.median

    companion object {
        /**
         * Fewer completed stays than this and the median is an anecdote. Five is a low
         * bar deliberately: the alternative to a weak estimate here is no estimate at
         * all, and the card says which it is looking at either way.
         */
        const val ENOUGH = 5
    }
}
