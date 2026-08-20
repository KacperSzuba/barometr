package pl.barometr.http

import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import pl.barometr.http.internal.HostRateLimiters
import pl.barometr.http.internal.RobotsGate
import java.time.Clock

@Configuration
class HttpPlatformConfiguration {

    @Bean
    fun rateLimiterRegistry(meterRegistry: ObjectProvider<MeterRegistry>): RateLimiterRegistry =
        RateLimiterRegistry.ofDefaults().also { registry ->
            // Optional binding: platform-http does not force Actuator onto anything
            // that only needs to fetch a URL. When a meter registry is present,
            // throttling becomes visible on a dashboard rather than showing up as
            // unexplained slowness.
            meterRegistry.ifAvailable { meters ->
                TaggedRateLimiterMetrics.ofRateLimiterRegistry(registry).bindTo(meters)
            }
        }

    /**
     * One gate for every source: robots.txt belongs to a host, not to a client.
     */
    @Bean
    fun robotsGate(restClientBuilder: RestClient.Builder, clock: Clock): RobotsGate =
        RobotsGate(restClientBuilder.build(), clock)

    @Bean
    fun sourceHttpClientFactory(
        restClientBuilder: RestClient.Builder,
        rateLimiterRegistry: RateLimiterRegistry,
        robotsGate: RobotsGate,
    ): SourceHttpClientFactory =
        SourceHttpClientFactory(restClientBuilder, HostRateLimiters(rateLimiterRegistry), robotsGate)
}
