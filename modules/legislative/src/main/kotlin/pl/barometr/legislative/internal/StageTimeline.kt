package pl.barometr.legislative.internal

import java.time.ZoneOffset

/**
 * Lays the register's stages out in time.
 *
 * Two rules, and both come from what the source actually publishes.
 *
 * A stage runs until the next one starts. When both fall on the same day — a second
 * reading, a return to committee and a third reading all happened on 29 November 2023
 * in one real process — it runs to the end of that day instead, because the register
 * dates stages and does not time them. The two periods then overlap, which the schema
 * allows on purpose: saying the draft was at both stages that day is true, and saying
 * it was at neither would not be.
 *
 * A stage the register did not date is dropped. The whole point of this table is
 * answering what the status was on a given day, and a period with no beginning cannot
 * take part in that answer — the schema refuses it outright, which is the same
 * judgement made one level down.
 */
object StageTimeline {

    fun of(stages: List<SejmProcessStage>): List<StageFact> {
        val dated = stages
            .filter { it.date != null }
            .sortedWith(compareBy({ it.date }, { it.ordinal }))

        return dated.mapIndexed { position, stage ->
            val day = requireNotNull(stage.date) { "filtered to dated stages" }
            val next = dated.getOrNull(position + 1)?.date
            val previous = dated.getOrNull(position - 1)?.let { it.stage ?: LegislativeStage.UNKNOWN }
            val current = stage.stage ?: LegislativeStage.UNKNOWN

            StageFact(
                stage = current,
                from = day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                until = next?.let { maxOf(it, day.plusDays(1)) }?.atStartOfDay(ZoneOffset.UTC)?.toInstant(),
                ordinal = stage.ordinal,
                sourceLabel = stage.sourceLabel,
                isException = previous != null && !LegislativePath.allows(previous, current),
            )
        }
    }
}
