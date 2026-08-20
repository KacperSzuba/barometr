package pl.barometr.http

import java.time.Duration

data class HttpPolicy(
    /** Enforced per host by a token bucket, not left to each connector. */
    val requestsPerSecond: Double,
    val userAgent: String = DEFAULT_USER_AGENT,
    val maxAttempts: Int = 4,
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val requestTimeout: Duration = Duration.ofSeconds(60),
    val robots: RobotsPolicy = RobotsPolicy.Respect,
) {
    companion object {
        const val DEFAULT_USER_AGENT = "BarometrBot/1.0 (+https://barometr.pl/bot)"
    }
}
