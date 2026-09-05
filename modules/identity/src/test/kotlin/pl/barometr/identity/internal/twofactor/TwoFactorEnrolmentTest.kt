package pl.barometr.identity.internal.twofactor

import org.junit.jupiter.api.Test
import pl.barometr.identity.api.UserId
import pl.barometr.shared.Ids
import pl.barometr.testing.TestClock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Turning a second factor on, and the step that most implementations skip.
 *
 * Scanning a QR image is not the same as being able to read a code from it. Until
 * somebody proves the second, nothing about their sign-in may change — otherwise a
 * mistyped setup locks them out of their own account, which is the failure this feature
 * is supposed to prevent rather than cause.
 */
class TwoFactorEnrolmentTest {

    private val clock = TestClock()
    private val secrets = InMemoryTwoFactorSecrets()
    private val recoveryCodes = InMemoryRecoveryCodes()
    private val codes = TotpCodes(clock)
    private val trustedDevices = InMemoryTrustedDevices()
    private val properties = TwoFactorProperties(encryptionKey = "k", encryptionSalt = "5c0744940b5c369b")

    private val trust = DeviceTrust(trustedDevices, properties, clock)
    private val enrolment = TwoFactorEnrolment(secrets, recoveryCodes, trust, codes, properties, clock)

    private val ewa = UserId(Ids.next())

    @Test
    fun `scanning the code changes nothing until a code comes back`() {
        val setup = enrolment.beginEnrolment(ewa, "ewa@example.test")

        assertTrue(setup.secret.isNotBlank())
        assertTrue(setup.setupUri.startsWith("otpauth://totp/Barometr"), setup.setupUri)
        assertTrue(setup.setupUri.contains("secret=${setup.secret}"))

        val status = enrolment.statusOf(ewa)
        assertFalse(status.enabled, "an unconfirmed set-up is not a second factor")
        assertTrue(status.enrolmentStarted)
    }

    @Test
    fun `a wrong code does not turn it on`() {
        enrolment.beginEnrolment(ewa, "ewa@example.test")

        assertFailsWith<InvalidTwoFactorCodeException> { enrolment.confirmEnrolment(ewa, "000000") }
        assertFalse(enrolment.statusOf(ewa).enabled)
    }

    @Test
    fun `a code from the authenticator turns it on and hands over ten recovery codes`() {
        val recovery = enable()

        assertEquals(10, recovery.size)
        assertEquals(10, recovery.distinct().size, "ten of the same code is one code")
        assertTrue(recovery.all { it.matches(Regex("[CDFGHJKLMNPQRSTVWXYZ2-9]{4}(-[CDFGHJKLMNPQRSTVWXYZ2-9]{4}){3}")) })

        val status = enrolment.statusOf(ewa)
        assertTrue(status.enabled)
        assertEquals(10, status.recoveryCodesLeft)
    }

    @Test
    fun `an account that already has one cannot be quietly re-enrolled`() {
        enable()

        assertFailsWith<TwoFactorAlreadyEnabledException> { enrolment.beginEnrolment(ewa, "ewa@example.test") }
    }

    @Test
    fun `minting new recovery codes retires the old ones`() {
        val first = enable()

        val second = enrolment.mintRecoveryCodes(ewa)

        assertTrue(first.intersect(second.toSet()).isEmpty(), "a new set is a new set")
        assertEquals(10, enrolment.statusOf(ewa).recoveryCodesLeft)
    }

    /**
     * Somebody signed in is not necessarily the account's owner — an unlocked laptop is
     * the case this guards against.
     */
    @Test
    fun `turning it off takes a code, and a wrong one leaves it on`() {
        enable()

        assertFailsWith<InvalidTwoFactorCodeException> { enrolment.confirmDisable(ewa, "000000") }
        assertTrue(enrolment.statusOf(ewa).enabled)

        enrolment.confirmDisable(ewa, AuthenticatorApp.codeFor(secretOf(), clock.instant()))
        assertFalse(enrolment.statusOf(ewa).enabled)
    }

    @Test
    fun `a recovery code turns it off too, for the phone that is gone`() {
        val recovery = enable()

        enrolment.confirmDisable(ewa, recovery.first())

        assertFalse(enrolment.statusOf(ewa).enabled)
    }

    /** Ten passwords of last resort for an account with no second factor is not a state to leave behind. */
    @Test
    fun `turning it off takes the recovery codes with it`() {
        enable()

        enrolment.confirmDisable(ewa, AuthenticatorApp.codeFor(secretOf(), clock.instant()))

        assertEquals(0, enrolment.statusOf(ewa).recoveryCodesLeft)
    }

    @Test
    fun `an account without one cannot turn it off`() {
        assertFailsWith<TwoFactorNotEnabledException> { enrolment.confirmDisable(ewa, "000000") }
    }

    /**
     * A device trusted to skip a factor that has just been removed would be a way in with
     * the password alone, granted by the act of removing the protection.
     */
    @Test
    fun `turning it off forgets the devices allowed to skip it`() {
        enable()
        val remembered = trust.rememberDevice(ewa, "Mozilla/5.0 (Macintosh)")

        enrolment.confirmDisable(ewa, AuthenticatorApp.codeFor(secretOf(), clock.instant()))

        assertFalse(trust.trusts(ewa, remembered))
        assertEquals(emptyList(), trust.devicesTrustedBy(ewa))
    }

    private fun enable(): List<String> {
        enrolment.beginEnrolment(ewa, "ewa@example.test")

        return enrolment.confirmEnrolment(ewa, AuthenticatorApp.codeFor(secretOf(), clock.instant()))
    }

    private fun secretOf(): String = checkNotNull(secrets.forUser(ewa.value)).secret
}
