package pl.barometr.connectors.sejm

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.barometr.http.HttpPolicy
import pl.barometr.http.SourceHttpClientFactory
import tools.jackson.databind.ObjectMapper
import java.net.URI

/**
 * Endpoints and pace in configuration, not in code.
 *
 * Trivial for one connector; it stops being trivial at the BIP framework, where
 * thousands of municipal sites are handled by a handful of adapters differing only
 * in their settings. Establishing the pattern now makes that work configuration
 * rather than a rewrite.
 */
@ConfigurationProperties(prefix = "app.connectors.sejm")
data class SejmProperties(
    val baseUrl: URI = URI.create("https://api.sejm.gov.pl"),
    val requestsPerSecond: Double = 2.0,
    val proceedingsPerChunk: Int = SejmConnector.DEFAULT_PROCEEDINGS_PER_CHUNK,
)

@Configuration
class SejmConnectorConfiguration {

    @Bean
    fun sejmApiClient(
        httpClientFactory: SourceHttpClientFactory,
        properties: SejmProperties,
        objectMapper: ObjectMapper,
    ): SejmApiClient = SejmApiClient(
        httpClient = httpClientFactory.create(
            HttpPolicy(requestsPerSecond = properties.requestsPerSecond),
        ),
        baseUrl = properties.baseUrl,
        json = objectMapper,
    )

    @Bean
    fun sejmConnector(
        api: SejmApiClient,
        properties: SejmProperties,
        objectMapper: ObjectMapper,
    ): SejmConnector = SejmConnector(
        api = api,
        payloads = CanonicalJsonPayload(objectMapper),
        proceedingsPerChunk = properties.proceedingsPerChunk,
    )
}
