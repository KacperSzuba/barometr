package pl.barometr.legislative.internal

import java.time.LocalDate

/**
 * One stage as the register listed it.
 *
 * [date] is nullable because the register genuinely omits it — the closing entry
 * never carries one, and some stages of a process that never really started carry
 * none either. [stage] is nullable for a different reason: the register described
 * something this model has no name for. Both are kept apart from the mapped value so
 * the caller can decide, and so [sourceLabel] can say what was actually written.
 */
data class SejmProcessStage(
    /** Position in the register's own list — the only way to order stages of one day. */
    val ordinal: Int,
    val date: LocalDate?,
    val stage: LegislativeStage?,
    val sourceLabel: String,
)
