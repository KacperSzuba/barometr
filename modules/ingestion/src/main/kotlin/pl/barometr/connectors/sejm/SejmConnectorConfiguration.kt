package pl.barometr.connectors.sejm

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.barometr.http.HttpPolicy
import pl.barometr.http.SourceHttpClientFactory
import tools.jackson.databind.ObjectMapper

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
