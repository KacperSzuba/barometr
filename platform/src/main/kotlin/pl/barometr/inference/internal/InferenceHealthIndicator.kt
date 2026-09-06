package pl.barometr.inference.internal

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import pl.barometr.inference.InferenceClient
import pl.barometr.inference.InferenceReadiness
import org.springframework.stereotype.Component

/**
 * Reports whether the inference service is there, under `inference` in the health group.
 *
 * Not `DOWN` when the service is running its fallback adapter: that service is healthy
 * and answering, and it says so on every response it produces. Turning a deliberate,
 * announced degradation into a red health check would mean a deployment that cannot tell
 * "no model key configured" from "the models failed to load".
 */
@Component("inference")
class InferenceHealthIndicator(private val inference: InferenceClient) : HealthIndicator {

    override fun health(): Health = when (val readiness = inference.readiness()) {
        is InferenceReadiness.Ready -> Health.up()
            .withDetail("model", readiness.model)
            .withDetail("modelVersion", readiness.modelVersion)
            .withDetail("dimension", readiness.dimension)
            .withDetail("generative", readiness.generative)
            .build()

        is InferenceReadiness.NotReady -> Health.outOfService()
            .withDetail("reason", readiness.reason)
            .build()

        is InferenceReadiness.Unreachable -> Health.down()
            .withDetail("reason", readiness.reason)
            .build()
    }
}
