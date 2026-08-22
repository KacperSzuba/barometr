package pl.barometr.alerts.internal

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component

/**
 * Whether the mail is arriving.
 *
 * The number this product lives or dies by, after the alerts themselves: a sending
 * domain that accumulates bounces and complaints stops reaching inboxes at all, and by
 * the time somebody notices, the alerts have been silently missing for weeks.
 *
 * Gauges over the tables rather than counters incremented as things happen, because the
 * question is "how many are in this state", which survives a restart — a counter would
 * reset to zero and report a perfect record.
 */
@Component
class EmailDeliveryMetrics(private val deliveries: EmailDeliveryRepository) : MeterBinder {

    override fun bindTo(meters: MeterRegistry) {
        DeliveryStatus.entries.forEach { status ->
            Gauge.builder("alerts.email.deliveries") { deliveries.countOf(status).toDouble() }
                .tag("status", status.wireName)
                .description("Digests by what became of their mail")
                .register(meters)
        }

        SuppressionReason.entries.forEach { reason ->
            Gauge.builder("alerts.email.suppressions") { deliveries.countSuppressed(reason).toDouble() }
                .tag("reason", reason.wireName)
                .description("Addresses nothing is sent to again, by why")
                .register(meters)
        }
    }
}
