package pl.barometr.legislative.internal

import java.time.Instant

/**
 * Where a draft is, what is expected next, and when — with the nature of every date
 * stated rather than implied.
 *
 * [expectedNextBy] is a guess from historical medians and [hardDeadline] is a date
 * fixed by somebody else. They are separate fields for the reason the specification
 * gives them as separate columns: a reader who mistakes the first for the second acts
 * on a date that was never promised, and this product cannot afford that once.
 */
data class DraftStatus(
    val currentStage: LegislativeStage,
    val since: Instant,
    /**
     * The day the draft left this stage, which for a government draft is the day it
     * left this register altogether. Null while it is still there, which is the answer
     * for every draft this register is still the one describing.
     */
    val until: Instant?,
    /** The source's own word for where the draft is, which may be finer than the model. */
    val sourceLabel: String?,
    /** Null once the draft is closed, or at a stage the process does not lead out of. */
    val expectedNext: LegislativeStage?,
    /** An estimate. Null when the archive holds too few completed stays to measure one. */
    val expectedNextBy: Instant?,
    val hardDeadline: HardDeadline?,
    /** Set when the draft has sat here for more than twice the usual stay. */
    val stalledSince: Instant?,
)
