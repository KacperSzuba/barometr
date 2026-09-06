package pl.barometr.inference

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Where the inference service is, and how this application announces itself to it.
 *
 * The service is stateless and holds no model of who its callers are, so [apiKey] is the
 * whole of the authentication: one shared secret, presented on every request. It is empty
 * by default because a developer running `docker compose up` should not have to invent a
 * secret to see the system work; `application-prod.yml` binds it to an environment
 * variable with no fallback, so production stops rather than talking to an open service.
 *
 * [clientId] is not a credential. The service bills its daily token budget against it and
 * tags its own metrics with it, which is why it is separate from the key: the key says
 * this is Barometr, the id says which deployment of it.
 */
@ConfigurationProperties(prefix = "app.ai")
data class InferenceProperties(
    val baseUrl: String = "http://localhost:8000",
    val apiKey: String = "",
    val clientId: String = "barometr",
    /**
     * Deliberately short. Reaching the service is a connection on the same network; one
     * that takes longer than this is a service that is not there, and finding that out
     * quickly is the point of asking.
     */
    val connectTimeout: Duration = Duration.ofSeconds(2),
    /**
     * How long to wait for the readiness answer. Short for the same reason: a health
     * endpoint that waits out a long timeout reports nothing and times out itself.
     * Inference calls will need their own, much longer, budget — a summary on a cold
     * model is measured in tens of seconds — and that setting arrives with the first
     * such call rather than sitting here unread.
     */
    val readinessTimeout: Duration = Duration.ofSeconds(5),
)
