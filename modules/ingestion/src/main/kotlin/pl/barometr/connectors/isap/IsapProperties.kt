package pl.barometr.connectors.isap

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/**
 * Endpoint, pace and window in configuration rather than in code.
 *
 * The base URL carries the `/eli` prefix: ISAP's API is served from the same host
 * as the Sejm's, and pointing both connectors at the bare host would leave the path
 * split between configuration and code.
 */
@ConfigurationProperties(prefix = "app.connectors.isap")
data class IsapProperties(
    val baseUrl: URI = URI.create("https://api.sejm.gov.pl/eli"),
    /** The same host and the same courtesy as the Sejm connector. */
    val requestsPerSecond: Double = 2.0,
    val pageSize: Int = IsapConnector.DEFAULT_PAGE_SIZE,
    val incrementalLookback: Duration = IsapConnector.DEFAULT_INCREMENTAL_LOOKBACK,
)
