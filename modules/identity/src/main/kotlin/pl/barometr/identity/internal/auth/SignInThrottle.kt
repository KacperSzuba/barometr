package pl.barometr.identity.internal.auth

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.barometr.platform.RateLimiter
import pl.barometr.shared.ContentHash

/**
 * What stands between a password field and an unlimited number of guesses.
 *
 * **A correct password is never refused here.** The account counter is spent by
 * failures only, so somebody who knows their password gets in on the first try however
 * many times a stranger has guessed at their address — which is the difference between
 * a throttle and a lockout, and the reason a lockout is not what this is: an account
 * anybody can disable by typing the wrong password ten times is a denial of service
 * with a login form for a console.
 *
 * **The address counter is spent before anything expensive happens.** BCrypt is
 * deliberately slow, so a login route that hashes first and counts afterwards hands out
 * CPU to anyone who asks; this is consulted before the password is looked at.
 *
 * The counting itself is [RateLimiter], which is a row in Postgres rather than a field:
 * a counter held in a process is a counter per replica, and two instances would mean
 * twice the attempts. Its writes are in their own transaction, so a failed sign-in
 * rolling back does not hand the attempt back to whoever made it.
 */
@Component
class SignInThrottle(
    private val limiter: RateLimiter,
    private val properties: SignInThrottleProperties,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Counts one attempt from this address, and refuses when there have been too many.
     *
     * An address the request did not carry is not counted: with `remoteAddr` unset there
     * is nothing to key on, and keying every such request together would put every
     * caller the deployment cannot see into one bucket that empties immediately.
     */
    fun admitAttemptFrom(from: ClientFingerprint) {
        val address = from.clientIp ?: return

        if (!limiter.consume("signin:ip:$address", properties.perAddress, properties.window).allowed) {
            refuse("address")
        }
    }

    /**
     * Counts one failed attempt against this account, and refuses once there have been
     * too many.
     *
     * The address is deliberately not part of the key: spraying one password across a
     * botnet is the attack this exists for, and it presents a different address every
     * time.
     */
    fun countFailedAttempt(email: String) {
        if (!limiter.consume(accountBucket(email), properties.perAccount, properties.window).allowed) {
            refuse("account")
        }
    }

    /**
     * The address, hashed, because the counter's rows live in `platform` where nobody
     * expects to find who anybody is. Nothing reads the key back — a bucket only ever
     * has to be found again, which a hash does exactly as well — and the system's own
     * SHA-256 is what does it, rather than a second digest written here.
     */
    private fun accountBucket(email: String): String =
        "signin:account:${ContentHash.of(email.toByteArray()).hex}"

    private fun refuse(bucket: String): Nothing {
        meters.counter("identity.signin.throttled", "bucket", bucket).increment()
        log.warn("Sign-in refused: too many attempts counted against one {}", bucket)
        throw TooManyAttemptsException()
    }
}
