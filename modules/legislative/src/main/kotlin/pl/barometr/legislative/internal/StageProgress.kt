package pl.barometr.legislative.internal

/**
 * How far along the path a stage sits, as a fraction.
 *
 * The specification's first-named signal — closer to enactment is more important —
 * turned into a number, and the only place in this system that may turn it into one:
 * the order it reads is [LegislativeStage]'s declaration order, which is the canonical
 * order of the path and is documented as load-bearing there. A second copy of that
 * order somewhere else would be a second answer to "what comes before what".
 *
 * Two stages are deliberately not on the scale.
 *
 * [LegislativeStage.UNKNOWN] is a stage the source described in words this model has
 * no name for. It sits last in the enum for want of anywhere better, and reading that
 * position as "nearly enacted" would rank our own blind spot above a third reading.
 * It scores as the start of the path instead: an unreadable stage is a matter we
 * cannot place, and placing it low is the honest direction to be wrong in.
 *
 * A veto is likewise not progress. It comes late in the declaration order because that
 * is when it happens, but a vetoed bill is further from becoming law than one waiting
 * for a signature, so it is pinned to where it actually goes back to.
 */
object StageProgress {

    private val PATH: List<LegislativeStage> =
        LegislativeStage.entries.filter { it != LegislativeStage.UNKNOWN && it != LegislativeStage.PRESIDENT_VETO }

    private val BY_STAGE: Map<LegislativeStage, Double> =
        PATH.withIndex().associate { (position, stage) ->
            // The last stage on the path is not 1.0: that is reserved for an act
            // published, which is a different thing from a bill signed.
            stage to (position + 1).toDouble() / (PATH.size + 1)
        }

    private val VETO_PROGRESS = BY_STAGE.getValue(LegislativeStage.THIRD_READING)

    /** 0 for a stage this cannot place, which is the honest direction to be wrong in. */
    fun of(stage: LegislativeStage?): Double = when (stage) {
        null, LegislativeStage.UNKNOWN -> 0.0
        LegislativeStage.PRESIDENT_VETO -> VETO_PROGRESS
        else -> BY_STAGE.getValue(stage)
    }
}
