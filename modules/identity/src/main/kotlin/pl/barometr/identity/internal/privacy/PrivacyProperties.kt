package pl.barometr.identity.internal.privacy

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * How long the things this system makes about a person are kept, bound from the
 * `app.identity.privacy` block.
 *
 * Retention is a policy rather than a constant, and the reason it is here rather than
 * spread through the contexts is that a data-protection register has to be able to name
 * one place where the answer is written down.
 */
@ConfigurationProperties("app.identity.privacy")
data class PrivacyProperties(
    /**
     * How long a finished export can be downloaded. A week: long enough to survive a
     * holiday, short enough that a file containing everything about somebody is not
     * sitting in object storage a year later.
     */
    val exportRetention: Duration = Duration.ofDays(7),
    /**
     * How long a revoked or expired session, refresh token or trusted device stays on
     * record. Ninety days: long enough to answer "who signed in from where in March",
     * which is what somebody investigating a compromise asks, and not longer.
     */
    val credentialRetention: Duration = Duration.ofDays(90),
) {
    init {
        require(!exportRetention.isNegative && !exportRetention.isZero) { "An export lives a positive duration" }
        require(!credentialRetention.isNegative) { "Credential retention is not negative" }
    }
}
