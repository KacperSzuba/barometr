package pl.barometr.connectors.isap

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.barometr.connectors.support.CanonicalJsonPayload
import pl.barometr.http.HttpPolicy
import pl.barometr.http.SourceHttpClientFactory
import tools.jackson.databind.ObjectMapper
import java.time.Clock

@Configuration
class IsapConnectorConfiguration {

    @Bean
    fun isapApiClient(
        httpClientFactory: SourceHttpClientFactory,
        properties: IsapProperties,
        objectMapper: ObjectMapper,
    ): IsapApiClient = IsapApiClient(
        httpClient = httpClientFactory.create(
            HttpPolicy(requestsPerSecond = properties.requestsPerSecond),
        ),
        baseUrl = properties.baseUrl,
        json = objectMapper,
    )

    @Bean
    fun isapConnector(
        api: IsapApiClient,
        properties: IsapProperties,
        objectMapper: ObjectMapper,
        clock: Clock,
    ): IsapConnector = IsapConnector(
        api = api,
        payloads = CanonicalJsonPayload(objectMapper),
        clock = clock,
        pageSize = properties.pageSize,
        incrementalLookback = properties.incrementalLookback,
    )
}
