package pl.barometr.legislative.internal

import java.time.Instant

/**
 * One recorded fact: this draft was at this stage over this period.
 *
 * That sentence is also the row's identity in the database, which is what makes a
 * re-read free and a correction visible — a changed period is a different fact, kept
 * beside the old one rather than overwriting it.
 */
data class StageFact(
    val stage: LegislativeStage,
    val from: Instant,
    /** Null while the draft is still there. */
    val until: Instant?,
    /** Position in the register's list, the only tiebreaker between stages of one day. */
    val ordinal: Int,
    /** What the source called it, including when this model had no name for it. */
    val sourceLabel: String,
    /** The model did not expect this step. Recorded, never refused. */
    val isException: Boolean,
)
