package pl.barometr.identity.internal.twofactor

import org.junit.jupiter.api.Test
import pl.barometr.identity.api.UserId
import pl.barometr.shared.Ids
import pl.barometr.testing.TestClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Do not ask me for a code on this laptop for a month."
 *
 * Every test here is about the cost of that bargain rather than the convenience: the
 * token is a credential, it runs out on its own, it belongs to one account, and it stops
 * working the moment the factor it skips is removed.
 */
class DeviceTrustTest {

    private val clock = TestClock()
    private val devices = InMemoryTrustedDevices()
    private val properties = TwoFactorProperties(encryptionKey = "k", encryptionSalt = "5c0744940b5c369b")

    private val trust = DeviceTrust(devices, properties, clock)

    private val ewa = UserId(Ids.next())
    private val marek = UserId(Ids.next())

    @Test
    fun `a remembered device skips the second factor`() {
        val token = trust.rememberDevice(ewa, "Mozilla/5.0 (Macintosh)")

        assertTrue(trust.trusts(ewa, token))
    }

    @Test
    fun `a device nobody remembered skips nothing`() {
        assertFalse(trust.trusts(ewa, "nie-ma-takiego-tokenu"))
        assertFalse(trust.trusts(ewa, null), "no token is not a trusted device")
    }

    /** A token is a device's, and one belonging to another account is a wrong answer rather than a weak one. */
    @Test
    fun `somebody else's token does not get anybody in`() {
        val token = trust.rememberDevice(marek, "Mozilla/5.0 (Macintosh)")

        assertFalse(trust.trusts(ewa, token))
    }

    @Test
    fun `trust runs out on its own, used or not`() {
        val token = trust.rememberDevice(ewa, "Mozilla/5.0 (Macintosh)")

        clock.advanceBy(properties.deviceTrustFor.minus(Duration.ofHours(1)))
        assertTrue(trust.trusts(ewa, token), "still inside the month")

        clock.advanceBy(Duration.ofHours(2))
        assertFalse(trust.trusts(ewa, token), "and out of it")
    }

    @Test
    fun `using a device is recorded, so somebody can see which laptop it is`() {
        val token = trust.rememberDevice(ewa, "Mozilla/5.0 (Macintosh)")
        clock.advanceBy(Duration.ofDays(2))

        trust.trusts(ewa, token)

        assertEquals(clock.instant(), trust.devicesTrustedBy(ewa).single().lastUsedAt)
    }

    @Test
    fun `forgetting one device leaves the others alone`() {
        val laptop = trust.rememberDevice(ewa, "Mozilla/5.0 (Macintosh)")
        val phone = trust.rememberDevice(ewa, "Mozilla/5.0 (iPhone)")
        val listed = trust.devicesTrustedBy(ewa).single { it.userAgent == "Mozilla/5.0 (Macintosh)" }

        trust.forgetDevice(ewa, listed.id)

        assertFalse(trust.trusts(ewa, laptop))
        assertTrue(trust.trusts(ewa, phone))
    }

    @Test
    fun `somebody else's device cannot be forgotten, and is not confirmed to exist`() {
        trust.rememberDevice(marek, "Mozilla/5.0 (Macintosh)")
        val theirs = trust.devicesTrustedBy(marek).single()

        assertFailsWith<UnknownTrustedDeviceException> { trust.forgetDevice(ewa, theirs.id) }
        assertEquals(1, trust.devicesTrustedBy(marek).size)
    }

    @Test
    fun `a device already forgotten cannot be forgotten twice`() {
        trust.rememberDevice(ewa, "Mozilla/5.0 (Macintosh)")
        val listed = trust.devicesTrustedBy(ewa).single()
        trust.forgetDevice(ewa, listed.id)

        assertFailsWith<UnknownTrustedDeviceException> { trust.forgetDevice(ewa, listed.id) }
    }

    /** The button somebody presses after losing a laptop: ask for a code everywhere again. */
    @Test
    fun `forgetting everything asks for a code everywhere again`() {
        val laptop = trust.rememberDevice(ewa, "Mozilla/5.0 (Macintosh)")
        val phone = trust.rememberDevice(ewa, "Mozilla/5.0 (iPhone)")
        val theirs = trust.rememberDevice(marek, "Mozilla/5.0 (Macintosh)")

        assertEquals(2, trust.forgetEveryDevice(ewa))

        assertFalse(trust.trusts(ewa, laptop))
        assertFalse(trust.trusts(ewa, phone))
        assertTrue(trust.trusts(marek, theirs), "and nobody else's account is touched")
    }

    @Test
    fun `a device nobody trusts is not listed`() {
        assertEquals(emptyList(), trust.devicesTrustedBy(ewa))
        assertEquals(0, trust.forgetEveryDevice(ewa))
    }
}
