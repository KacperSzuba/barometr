package pl.barometr.identity.internal.twofactor

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

/**
 * The codes an authenticator app produces, and whether the one somebody typed is among
 * them.
 *
 * **The previous step counts too.** A phone's clock and a server's are never exactly the
 * same, and a code typed in the last second of its window arrives in the next one. One
 * step either side is what every implementation of this allows; less, and perfectly
 * honest people are told they are wrong.
 *
 * The generator itself is [TimeBasedOneTimePasswordGenerator]: the counter derived from
 * the clock, the truncation and the comparison are the three things a hand-rolled TOTP
 * gets wrong, and none of them is domain knowledge worth owning.
 */
@Component
class TotpCodes(private val clock: Clock) {

    private val generator = TimeBasedOneTimePasswordGenerator()
    private val random = SecureRandom()

    /** A fresh shared secret, in the base32 an authenticator expects to be given. */
    fun newSecret(): String = base32(ByteArray(SECRET_BYTES).also(random::nextBytes))

    /**
     * True when [code] is what this secret produces now, or produced one step ago.
     *
     * Compared through [MessageDigest.isEqual] rather than `==`, because a
     * string comparison stops at the first wrong character and how long that takes is
     * one more thing an attacker can measure.
     */
    fun matches(secret: String, code: String): Boolean {
        val key = SecretKeySpec(unbase32(secret) ?: return false, generator.algorithm)
        val typed = code.trim().toByteArray(StandardCharsets.UTF_8)

        return steps().any { at ->
            val expected = "%0${generator.passwordLength}d".format(generator.generateOneTimePassword(key, at))
            MessageDigest.isEqual(expected.toByteArray(StandardCharsets.UTF_8), typed)
        }
    }

    /**
     * The `otpauth://` URI an authenticator is set up from — what a QR code encodes.
     *
     * Rendered as an image by whoever shows it: a QR generator is a picture library, and
     * the contract here is the string it draws.
     */
    fun setupUri(secret: String, email: String): String {
        val label = encode("$ISSUER:$email")

        return "otpauth://totp/$label?secret=$secret&issuer=${encode(ISSUER)}&algorithm=SHA1&digits=6&period=30"
    }

    private fun steps(): List<Instant> = listOf(clock.instant(), clock.instant().minus(generator.timeStep))

    private fun base32(bytes: ByteArray): String {
        // Base32 as RFC 4648 defines it, which is what authenticators read. Written here
        // because the JDK ships base64 and not base32, and it is twenty lines of table
        // lookup with no decisions in it.
        val alphabet = BASE32_ALPHABET
        val out = StringBuilder()
        var buffer = 0
        var bits = 0

        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(alphabet[(buffer shr (bits - 5)) and 0x1f])
                bits -= 5
            }
        }
        if (bits > 0) out.append(alphabet[(buffer shl (5 - bits)) and 0x1f])

        return out.toString()
    }

    /** Null for anything that is not base32 — a secret nobody could have been given. */
    private fun unbase32(secret: String): ByteArray? {
        val out = mutableListOf<Byte>()
        var buffer = 0
        var bits = 0

        secret.trim().uppercase().forEach { character ->
            val index = BASE32_ALPHABET.indexOf(character).takeIf { it >= 0 } ?: return null
            buffer = (buffer shl 5) or index
            bits += 5
            if (bits >= 8) {
                out += ((buffer shr (bits - 8)) and 0xff).toByte()
                bits -= 8
            }
        }

        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        /** Twenty bytes: the size RFC 4226 recommends for an HMAC-SHA1 secret. */
        const val SECRET_BYTES = 20

        const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        /** What the authenticator shows beside the code. */
        const val ISSUER = "Barometr"
    }
}
