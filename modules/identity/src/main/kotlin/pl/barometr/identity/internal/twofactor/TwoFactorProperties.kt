package pl.barometr.identity.internal.twofactor

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * What the second factor needs to know, bound from the `app.identity.two-factor` block.
 *
 * The key has no production default, deliberately, and the application refuses to enrol
 * anybody without one: a TOTP secret stored in the clear is a second factor that a
 * database dump defeats, which is the one thing a second factor is bought to prevent.
 */
@ConfigurationProperties("app.identity.two-factor")
data class TwoFactorProperties(
    /** The key the shared secrets are encrypted with. Held by the application, never by the database. */
    val encryptionKey: String = "",
    /**
     * Hex-encoded salt for deriving the encryption key. Not a secret — it exists so that
     * the same password does not derive the same key in two deployments — but it must
     * not change once anything is enrolled, or every stored secret becomes unreadable.
     */
    val encryptionSalt: String = "",
    /**
     * How long the gap between the password and the code may stay open. Five minutes is
     * long enough to find a phone that is in another room and short enough that a proved
     * password does not sit around being worth something.
     */
    val challengeTtl: Duration = Duration.ofMinutes(5),
    /**
     * How many codes one challenge may be answered with before it is spent. Six digits
     * is a million guesses; five attempts makes brute force a matter of signing in again
     * and again, which is a thing that can be seen and rate-limited.
     */
    val maxAttempts: Int = 5,
    /** How many recovery codes are minted at once. Ten, which is what the specification asks for. */
    val recoveryCodes: Int = 10,
    /**
     * How long a device may skip the second factor once it has answered it. Thirty days
     * is what the specification asks for and what every product doing this settles on:
     * long enough that the factor stops being a daily tax, short enough that a laptop
     * left behind somewhere stops being a way in within a month.
     */
    val deviceTrustFor: Duration = Duration.ofDays(30),
) {
    init {
        require(maxAttempts in 1..20) { "A challenge allows between 1 and 20 attempts, got $maxAttempts" }
        require(recoveryCodes in 1..50) { "Between 1 and 50 recovery codes, got $recoveryCodes" }
        require(!deviceTrustFor.isNegative && !deviceTrustFor.isZero) { "Device trust lasts a positive duration" }
        require(!challengeTtl.isNegative && !challengeTtl.isZero) { "A challenge lives for a positive duration" }
    }
}
