package pl.barometr.legislative.internal

import java.time.Instant

/** One stage of a draft's history, as it was recorded. */
data class RecordedStage(
    val stage: LegislativeStage,
    val since: Instant,
    /** Null while the draft has not left it. */
    val until: Instant?,
    val ordinal: Int,
    val sourceLabel: String?,
    val isException: Boolean,
)
