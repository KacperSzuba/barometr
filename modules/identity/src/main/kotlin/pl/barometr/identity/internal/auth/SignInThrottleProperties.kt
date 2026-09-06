package pl.barometr.identity.internal.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * How much sign-in traffic one address, and one account, may spend.
 *
 * Two numbers because there are two attacks and they look nothing alike. A flood from
 * one address is trying every account, and is bounded by [perAddress] before a password
 * is ever hashed — bcrypt at the configured work factor is the expensive part of a
 * login, and an unbounded login route is a way to spend a server's CPU without holding
 * an account. Spraying one password across a botnet is trying one account from
 * everywhere, and no address-based number can see it; [perAccount] can, because the
 * account is the one thing every one of those requests has in common.
 */
@ConfigurationProperties("app.identity.sign-in")
data class SignInThrottleProperties(
    /**
     * Fifteen minutes: long enough that a guessing run cannot wait it out cheaply,
     * short enough that somebody who mistyped their password four times is not locked
     * out of their afternoon.
     */
    val window: Duration = Duration.ofMinutes(15),
    /**
     * Failed attempts against one account. Only failures are counted, so this number
     * can be small without ever standing between somebody and their own account.
     */
    val perAccount: Int = 10,
    /**
     * Attempts from one address, successful or not.
     *
     * Generous, because an address is a coarse bucket: an office behind one gateway
     * shares it, and the morning everybody signs in must not look like an attack. It is
     * a ceiling on the cheapest form of abuse rather than a fine-grained control — the
     * account number above is the one that does the precise work.
     */
    val perAddress: Int = 60,
) {
    init {
        require(!window.isNegative && !window.isZero) { "A throttle window is a positive duration" }
        require(perAccount > 0) { "An account must be allowed at least one attempt, got $perAccount" }
        require(perAddress > 0) { "An address must be allowed at least one attempt, got $perAddress" }
    }
}
