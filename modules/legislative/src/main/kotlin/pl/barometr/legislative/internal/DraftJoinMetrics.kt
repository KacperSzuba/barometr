package pl.barometr.legislative.internal

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component

/**
 * How much of the legislative process is a whole story rather than two halves.
 *
 * The counter [DraftIdentityMatcher] keeps says what each attempt decided; these two
 * gauges say where that leaves the archive — how many drafts have both registers
 * behind them, and how long the queue of the ones nobody could decide has grown. A
 * queue that only grows is the thresholds saying they are wrong.
 */
@Component
class DraftJoinMetrics(
    private val continuations: DraftContinuationRepository,
    private val candidates: DraftMatchCandidateRepository,
) : MeterBinder {

    override fun bindTo(meters: MeterRegistry) {
        Gauge.builder("legislative.draft_joins.recorded") { continuations.countContinuations() }
            .description("Government drafts joined to the print they became")
            .register(meters)

        Gauge.builder("legislative.draft_joins.awaiting_review") { candidates.countAwaitingReview() }
            .description("Joins waiting for a person to decide")
            .register(meters)
    }
}
