package pl.barometr.identity.internal.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Signing and lifetime settings, bound from the `app.jwt` block.
 *
 * The secret has no default: each profile supplies it, and the prod profile
 * deliberately omits a fallback so a missing `JWT_SECRET` stops the application
 * from starting rather than silently signing with a known key.
 */
@ConfigurationProperties(prefix = "app.jwt")
data class JwtProperties(
    val secret: String,
    val issuer: String,
    val audience: String,
    val accessTtl: Duration,
    val refreshTtl: Duration,
    val refreshGrace: Duration,
)
