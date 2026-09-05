package pl.barometr.identity.internal.twofactor

import org.junit.jupiter.api.Test
import pl.barometr.identity.api.UserId
import pl.barometr.shared.Ids
import pl.barometr.testing.TestClock
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The gap between a password and a code.
 *
 * Everything here is about what the gap must not become: a token that grants something,
 * a window that stays open, or a six-digit lock that can be guessed at until it opens.
 */
class TwoFactorSignInTest {

    private val clock = TestClock()
    private val secrets = InMemoryTwoFactorSecrets()
    private val recoveryCodes = InMemoryRecoveryCodes()
    private val challenges = InMemoryLoginChallenges()
    private val codes = TotpCodes(clock)
    private val trustedDevices = InMemoryTrustedDevices()
    private val properties = TwoFactorProperties(encryptionKey = "k", encryptionSalt = "5c0744940b5c369b")
    private val trust = DeviceTrust(trustedDevices, properties, clock)
    private val enrolment = TwoFactorEnrolment(secrets, recoveryCodes, trust, codes, properties, clock)

    private val signIn = TwoFactorSignIn(secrets, recoveryCodes, challenges, codes, enrolment, properties, clock)

    private val ewa = UserId(Ids.next())

    @Test
    fun `an account with no confirmed factor is not asked for one`() {
        assertEquals(false, signIn.isRequiredFor(ewa.value))

        enrolment.beginEnrolment(ewa, "ewa@example.test")

        assertEquals(false, signIn.isRequiredFor(ewa.value), "an unconfirmed set-up asks nothing of anybody")
    }

    @Test
    fun `the code from the authenticator answers the challenge`() {
        enable()
        val challenge = signIn.challengeFor(ewa.value)

        assertEquals(ewa.value, signIn.answerChallenge(challenge.id, currentCode()))
    }

    @Test
    fun `a challenge is worth one sign-in and no more`() {
        enable()
        val challenge = signIn.challengeFor(ewa.value)
        signIn.answerChallenge(challenge.id, currentCode())

        assertFailsWith<InvalidTwoFactorCodeException> { signIn.answerChallenge(challenge.id, currentCode()) }
    }

    @Test
    fun `a wrong code is refused and counted`() {
        enable()
        val challenge = signIn.challengeFor(ewa.value)

        assertFailsWith<InvalidTwoFactorCodeException> { signIn.answerChallenge(challenge.id, "000000") }
        assertEquals(1, challenges.byIdForUpdate(challenge.id)?.attempts)
    }

    /**
     * Six digits is a million guesses. A challenge that could be answered indefinitely
     * would be a second factor in name only.
     */
    @Test
    fun `a challenge guessed at too often is spent, even for the right code`() {
        enable()
        val challenge = signIn.challengeFor(ewa.value)

        repeat(properties.maxAttempts) {
            assertFailsWith<InvalidTwoFactorCodeException> { signIn.answerChallenge(challenge.id, "000000") }
        }

        assertFailsWith<InvalidTwoFactorCodeException> { signIn.answerChallenge(challenge.id, currentCode()) }
        assertEquals(true, challenges.byIdForUpdate(challenge.id)?.consumedAt != null)
    }

    @Test
    fun `a challenge nobody answered in time is worth nothing`() {
        enable()
        val challenge = signIn.challengeFor(ewa.value)

        clock.advanceBy(properties.challengeTtl.plus(Duration.ofSeconds(1)))

        assertFailsWith<InvalidTwoFactorCodeException> { signIn.answerChallenge(challenge.id, currentCode()) }
    }

    @Test
    fun `a challenge nobody opened is refused the same way as a wrong code`() {
        enable()

        assertFailsWith<InvalidTwoFactorCodeException> { signIn.answerChallenge(UUID.randomUUID(), currentCode()) }
    }

    @Test
    fun `a recovery code answers a challenge, once`() {
        val recovery = enable()
        val first = signIn.challengeFor(ewa.value)

        assertEquals(ewa.value, signIn.answerChallenge(first.id, recovery.first()))

        val second = signIn.challengeFor(ewa.value)
        assertFailsWith<InvalidTwoFactorCodeException> { signIn.answerChallenge(second.id, recovery.first()) }
        assertEquals(9, enrolment.statusOf(ewa).recoveryCodesLeft)
    }

    /** An authenticator that works must not spend the codes kept for when it does not. */
    @Test
    fun `answering with the authenticator spends no recovery code`() {
        enable()
        val challenge = signIn.challengeFor(ewa.value)

        signIn.answerChallenge(challenge.id, currentCode())

        assertEquals(10, enrolment.statusOf(ewa).recoveryCodesLeft)
    }

    private fun enable(): List<String> {
        enrolment.beginEnrolment(ewa, "ewa@example.test")

        return enrolment.confirmEnrolment(ewa, currentCode())
    }

    private fun currentCode(): String =
        AuthenticatorApp.codeFor(checkNotNull(secrets.forUser(ewa.value)).secret, clock.instant())
}
