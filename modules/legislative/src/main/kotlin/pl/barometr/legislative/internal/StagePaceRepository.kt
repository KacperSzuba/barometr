package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.legislative.internal.jooq.tables.references.STAGE_TRANSITION_LATEST
import java.math.BigDecimal
import java.time.Duration

/**
 * Measures how long stages take, out of the history already recorded. SQL only.
 *
 * Only *completed* stays count — a period with an open end is a stage the draft has
 * not left, and including it would report the archive's youngest bills as its
 * quickest.
 *
 * Measured over `stage_transition_latest` rather than the table, so a stay whose end
 * was corrected once is one observation and not two. The median is what every estimate
 * this system shows is built on, and counting a revised period twice would weight it by
 * how often the register happened to be re-read.
 */
@Repository
@Transactional(readOnly = true)
class StagePaceRepository(private val dsl: DSLContext) {

    fun measure(): StagePaces = StagePaces(byInitiator(STAY_IN_SECONDS) + overall(STAY_IN_SECONDS))

    private fun byInitiator(seconds: Field<BigDecimal>) =
        dsl.select(DRAFT.INITIATOR, STAGE_TRANSITION_LATEST.STAGE, median(seconds), DSL.count())
            .from(STAGE_TRANSITION_LATEST)
            .join(DRAFT).on(DRAFT.ID.eq(STAGE_TRANSITION_LATEST.DRAFT_ID))
            .where(STAGE_TRANSITION_LATEST.VALID_TO.isNotNull)
            .groupBy(DRAFT.INITIATOR, STAGE_TRANSITION_LATEST.STAGE)
            .fetch { record ->
                paceOf(
                    initiator = DraftInitiator.entries.firstOrNull { it.wireName == record.value1() },
                    stage = record.value2(),
                    medianSeconds = record.value3(),
                    observations = record.value4(),
                )
            }
            .filterNotNull()

    private fun overall(seconds: Field<BigDecimal>) =
        dsl.select(STAGE_TRANSITION_LATEST.STAGE, median(seconds), DSL.count())
            .from(STAGE_TRANSITION_LATEST)
            .where(STAGE_TRANSITION_LATEST.VALID_TO.isNotNull)
            .groupBy(STAGE_TRANSITION_LATEST.STAGE)
            .fetch { record ->
                paceOf(
                    initiator = null,
                    stage = record.value1(),
                    medianSeconds = record.value2(),
                    observations = record.value3(),
                )
            }
            .filterNotNull()

    private fun median(seconds: Field<BigDecimal>) = DSL.percentileCont(0.5).withinGroupOrderBy(seconds)

    private companion object {
        /**
         * How long a stay lasted, in seconds.
         *
         * A plain-SQL template because jOOQ's interval arithmetic does not cover
         * `timestamptz` subtraction, and this is Postgres's own way of asking. The
         * fields are bound, not written into the text.
         */
        val STAY_IN_SECONDS: Field<BigDecimal> = DSL.field(
            "extract(epoch from ({0} - {1}))",
            BigDecimal::class.java,
            STAGE_TRANSITION_LATEST.VALID_TO,
            STAGE_TRANSITION_LATEST.VALID_FROM,
        )
    }

    private fun paceOf(
        initiator: DraftInitiator?,
        stage: String?,
        medianSeconds: BigDecimal?,
        observations: Int,
    ): StagePace? {
        val known = stage?.let(LegislativeStage::of) ?: return null
        val median = medianSeconds ?: return null

        return StagePace(known, initiator, Duration.ofSeconds(median.toLong()), observations)
    }
}
