package pl.barometr.http.internal

import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import java.time.Duration
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One rate limiter per host, from Resilience4j.
 *
 * Replaces a hand-written token bucket. The point is not that the arithmetic was
 * hard — it is that a limiter from the registry reports its own metrics
 * (available permits, waiting threads, rejections) through Micrometer, so a
 * connector being throttled is visible on a dashboard instead of only as
 * unexplained slowness.
 */
class HostRateLimiters(private val registry: RateLimiterRegistry) {

    /** Blocks until this host allows another request. */
    fun acquire(host: String, requestsPerSecond: Double) {
        registry.rateLimiter(host) { configFor(requestsPerSecond) }.acquirePermission()
    }

    private fun configFor(requestsPerSecond: Double): RateLimiterConfig {
        // Resilience4j counts whole permits per refresh window, so a rate below one
        // request per second becomes one permit over a longer window rather than a
        // fractional permit.
        val (limit, period) = if (requestsPerSecond >= 1.0) {
            max(1, requestsPerSecond.roundToInt()) to Duration.ofSeconds(1)
        } else {
            1 to Duration.ofMillis((1_000.0 / requestsPerSecond).toLong())
        }

        return RateLimiterConfig.custom()
            .limitForPeriod(limit)
            .limitRefreshPeriod(period)
            // Generous: a connector waiting its turn is correct behaviour, and
            // failing the fetch instead would just turn into a retry that waits
            // anyway. Long enough to be a real bound, not a disguised deadlock.
            .timeoutDuration(ACQUIRE_TIMEOUT)
            .build()
    }

    private companion object {
        val ACQUIRE_TIMEOUT: Duration = Duration.ofMinutes(2)
    }
}
