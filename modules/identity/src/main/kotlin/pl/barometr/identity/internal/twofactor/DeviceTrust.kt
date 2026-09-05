package pl.barometr.identity.internal.twofactor

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import pl.barometr.shared.Ids
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

/**
 * "Do not ask me for a code on this laptop for a month."
 *
 * The bargain every product with a second factor eventually makes, and worth stating
 * rather than implying: **a remembered device signs in with the password alone.** The
 * token is therefore a credential — stored as a hash, expiring whether or not it is
 * used, and gone the moment the second factor is turned off or reset by an operator.
 *
 * What it buys is the reason the factor stays on at all. A code demanded every morning
 * from the same machine is a code somebody eventually disables the factor to stop.
 */
@Service
class DeviceTrust(
    private val devices: TrustedDevices,
    private val properties: TwoFactorProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    /**
     * Remembers this device and hands back the token that proves it.
     *
     * The plaintext exists here and nowhere else: it goes to the caller in the response
     * and to the database as a hash, which is the whole of its life on this side.
     */
    @Transactional
    fun rememberDevice(user: UserId, userAgent: String?): String {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(TOKEN_BYTES).also(random::nextBytes),
        )
        val now = clock.instant()

        devices.remember(
            RememberedDevice(
                id = Ids.next(),
                userId = user.value,
                tokenHash = hash(token),
                userAgent = userAgent,
                createdAt = now,
                expiresAt = now.plus(properties.deviceTrustFor),
                lastUsedAt = null,
                revokedAt = null,
            ),
        )

        log.info("Device remembered for {} until {}", user.value, now.plus(properties.deviceTrustFor))

        return token
    }

    /**
     * Whether this token lets this account skip the second factor now.
     *
     * The account is part of the question deliberately: a token is a device's, and one
     * that belongs to a different account is not a weaker answer but a wrong one.
     */
    @Transactional
    fun trusts(user: UserId, token: String?): Boolean {
        val remembered = token?.let { devices.byTokenHash(hash(it), clock.instant()) } ?: return false
        if (remembered.userId != user.value) return false

        devices.markUsed(remembered.id, clock.instant())

        return true
    }

    @Transactional(readOnly = true)
    fun devicesTrustedBy(user: UserId): List<RememberedDevice> = devices.liveFor(user.value, clock.instant())

    @Transactional
    fun forgetDevice(user: UserId, id: UUID) {
        if (!devices.revoke(user.value, id, clock.instant())) throw UnknownTrustedDeviceException(id.toString())
    }

    /**
     * Forgets every one of them, which is what turning the second factor off or having
     * an operator reset it must do: a device trusted to skip a factor that no longer
     * exists is a password-only sign-in nobody asked for.
     */
    @Transactional
    fun forgetEveryDevice(user: UserId): Int = devices.revokeAll(user.value, clock.instant())

    /**
     * Plain SHA-256, for the reason the refresh tokens give: the input is already
     * thirty-two random bytes chosen by us rather than a password chosen by a person.
     */
    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
