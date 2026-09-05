package pl.barometr.taxonomy.internal

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * How much of the archive carries an industry at all, as two gauges.
 *
 * The number that says whether routing by PKD means anything yet. A profile watching an
 * industry no act has been tagged with is a subscription to silence, and silence is
 * exactly what a working alert engine also looks like — so the size of the classified
 * set is not something to discover from a support ticket.
 */
@Component
class ClassificationCoverage(
    private val verdicts: IndustryVerdictRepository,
    private val meters: MeterRegistry,
) {

    @PostConstruct
    fun publishCoverage() {
        meters.gauge("taxonomy.verdicts", listOf(Tag.of("status", "accepted")), this) {
            it.verdicts.countAccepted().toDouble()
        }
        meters.gauge("taxonomy.verdicts", listOf(Tag.of("status", "pending")), this) {
            it.verdicts.countPending().toDouble()
        }
    }
}
