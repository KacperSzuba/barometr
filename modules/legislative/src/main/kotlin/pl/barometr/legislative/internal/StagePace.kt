package pl.barometr.legislative.internal

import java.time.Duration

/**
 * How long a stage usually takes, measured from the archive rather than assumed.
 *
 * The median, not the mean: a single bill parked in committee for two years would
 * drag an average far enough to make every estimate useless, and the median is what
 * the specification asks for — a base rate anybody can check, not a model nobody can
 * explain.
 */
data class StagePace(
    val stage: LegislativeStage,
    /** Null for the figure taken across all initiators. */
    val initiator: DraftInitiator?,
    val median: Duration,
    /** How many completed stays it was measured from. */
    val observations: Int,
)
