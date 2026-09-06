package pl.barometr.identity.internal.auth

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What the throttle counts, and what it keys those counts on.
 *
 * The keys are the design: an account counted per address would miss a botnet, and an
 * address counted per account would miss a sweep. Both are asserted here rather than
 * left to a comment, because nothing else in the build would notice a key that quietly
 * started carrying both.
 */
class SignInThrottleTest {

    private val limiter = CountingRateLimiter()
    private val properties = SignInThrottleProperties(
        window = Duration.ofMinutes(15),
        perAccount = 2,
        perAddress = 3,
    )
    private val throttle = SignInThrottle(limiter, properties, SimpleMeterRegistry())

    @Test
    fun `an address is admitted until its attempts run out`() {
        val from = ClientFingerprint(userAgent = null, clientIp = "198.51.100.7")

        repeat(properties.perAddress) { throttle.admitAttemptFrom(from) }

        assertFailsWith<TooManyAttemptsException> { throttle.admitAttemptFrom(from) }
    }

    @Test
    fun `an account stops answering once its failures run out`() {
        repeat(properties.perAccount) { throttle.countFailedAttempt("poslanka@example.test") }

        assertFailsWith<TooManyAttemptsException> { throttle.countFailedAttempt("poslanka@example.test") }
    }

    /** The attack the account counter exists for: one password, tried from everywhere. */
    @Test
    fun `failures against one account are counted together whatever address they came from`() {
        throttle.countFailedAttempt("poslanka@example.test")
        throttle.countFailedAttempt("poslanka@example.test")

        assertEquals(1, limiter.buckets.size, "one account is one bucket, whoever is guessing")
    }

    /** And its mirror: one address working through a list of accounts. */
    @Test
    fun `attempts from one address are counted together whatever account they name`() {
        val from = ClientFingerprint(userAgent = null, clientIp = "198.51.100.7")

        throttle.admitAttemptFrom(from)
        throttle.admitAttemptFrom(from)

        assertEquals(2, limiter.spentOn("signin:ip:198.51.100.7"))
    }

    /**
     * The counter's rows live in `platform`, where an address would be personal data
     * nobody put there deliberately.
     */
    @Test
    fun `an account is counted under a hash rather than under its address`() {
        throttle.countFailedAttempt("poslanka@example.test")

        val bucket = limiter.buckets.single()
        assertTrue(bucket.startsWith("signin:account:"), "keyed as an account bucket, got '$bucket'")
        assertTrue("poslanka" !in bucket, "the address itself must not appear in '$bucket'")
    }

    /**
     * With no address there is nothing to key on, and keying every such request together
     * would put every caller a deployment cannot see into one bucket that empties at once.
     */
    @Test
    fun `a request that carries no address is not counted against one`() {
        repeat(properties.perAddress * 2) { throttle.admitAttemptFrom(ClientFingerprint.UNKNOWN) }

        assertEquals(emptySet(), limiter.buckets)
    }
}
