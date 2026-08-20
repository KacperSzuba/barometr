package pl.barometr.connectors.sejm

import java.time.LocalDate

class SejmTerm internal constructor(
    val number: Int,
    val isCurrent: Boolean,
    val from: LocalDate?,
    val to: LocalDate?,
    /**
     * When prints in this term last changed, as the API reports it. The whole
     * incremental strategy rests on this one field.
     */
    val printsLastChangedAt: String?,
    /** The source's own tally, for checking a finished backfill against. */
    val printCount: Int,
) {
    fun overlaps(windowStart: LocalDate, windowEnd: LocalDate): Boolean {
        val start = from ?: return false
        // An ongoing term runs to today, so it always reaches the window's end.
        val end = to ?: LocalDate.MAX
        return !start.isAfter(windowEnd) && !end.isBefore(windowStart)
    }
}
