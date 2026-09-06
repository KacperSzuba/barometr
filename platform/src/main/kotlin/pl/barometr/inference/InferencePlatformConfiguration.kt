package pl.barometr.inference

import org.slf4j.LoggerFactory
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import pl.barometr.inference.internal.RestClientInferenceClient

/**
 * Wires the one client that may reach the inference service.
 *
 * The `RestClient.Builder` comes from Boot's auto-configuration, so every request is
 * instrumented for Micrometer and carries the trace without this class arranging it —
 * the same reason the connectors are built on it.
 */
@Configuration
class InferencePlatformConfiguration {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun inferenceClient(
        restClientBuilder: RestClient.Builder,
        properties: InferenceProperties,
    ): InferenceClient {
        if (properties.apiKey.isBlank()) {
            // A warning rather than a refusal: the service itself accepts an empty key
            // only outside production, and a developer with no key should still get a
            // running system. What must not happen is nobody noticing, so it is said on
            // every start rather than left in a configuration file nobody opens.
            log.warn(
                "app.ai.api-key is unset — calls to {} go out unauthenticated. " +
                    "Acceptable only against a local service.",
                properties.baseUrl,
            )
        }

        val timeouts = HttpClientSettings.defaults()
            .withTimeouts(properties.connectTimeout, properties.readinessTimeout)

        val restClient = restClientBuilder
            .baseUrl(properties.baseUrl)
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(timeouts))
            // Both headers on every request, because both are properties of this caller
            // rather than of any one call: the key says who is asking, the client id is
            // what the service bills its token budget against.
            .defaultHeader(API_KEY_HEADER, properties.apiKey)
            .defaultHeader(CLIENT_ID_HEADER, properties.clientId)
            .build()

        return RestClientInferenceClient(restClient)
    }

    private companion object {
        const val API_KEY_HEADER = "X-Api-Key"
        const val CLIENT_ID_HEADER = "X-Client-Id"
    }
}
