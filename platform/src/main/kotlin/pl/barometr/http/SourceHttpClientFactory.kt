package pl.barometr.http

import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import pl.barometr.http.internal.HostRateLimiters
import pl.barometr.http.internal.RestClientSourceHttpClient
import pl.barometr.http.internal.RobotsGate

/**
 * Builds a client per source, because pace and robots policy are per source.
 *
 * The underlying `RestClient.Builder` comes from Boot's auto-configuration, so
 * timeouts follow `spring.http.client.*` and every request is instrumented
 * without this class arranging any of it.
 */
class SourceHttpClientFactory(
    private val restClientBuilder: RestClient.Builder,
    private val rateLimiters: HostRateLimiters,
    private val robots: RobotsGate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun create(policy: HttpPolicy): SourceHttpClient {
        // Announced, not buried. An exemption that only lives in a config file is
        // one nobody remembers granting; this one is in the log of every start.
        (policy.robots as? RobotsPolicy.Exempt)?.let { exemption ->
            log.warn(
                "robots.txt is being overridden for this source. Stated basis: {}",
                exemption.legalBasis,
            )
        }

        return RestClientSourceHttpClient(
            restClient = restClientBuilder.build(),
            policy = policy,
            rateLimiters = rateLimiters,
            robots = robots,
        )
    }
}
