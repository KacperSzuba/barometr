package pl.barometr.legislative.internal

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component

/**
 * Where the archive's drafts are standing, and how many have stopped moving.
 *
 * Read from the rebuilt read model rather than computed here: a gauge that walked
 * every history on every scrape would cost more than the thing it measures.
 */
@Component
class DraftPathMetrics(private val statuses: DraftStatusRepository) : MeterBinder {

    override fun bindTo(meters: MeterRegistry) {
        LegislativeStage.entries.forEach { stage ->
            Gauge.builder("legislative.drafts.at_stage") { statuses.countByStage()[stage] ?: 0 }
                .tag("stage", stage.wireName)
                .description("Drafts whose latest recorded stage is this one")
                .register(meters)
        }

        Gauge.builder("legislative.drafts.stalled") { statuses.countStalled() }
            .description("Drafts that have sat at one stage for more than twice the usual stay")
            .register(meters)
    }
}
