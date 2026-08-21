package pl.barometr.alerts.internal

import java.time.Instant

/** One line of the log that answers "why did I not hear about this". */
data class RecordedDecision(
    val subjectKind: String,
    val subjectId: String,
    val decision: String,
    val reason: String,
    val decidedAt: Instant,
)
