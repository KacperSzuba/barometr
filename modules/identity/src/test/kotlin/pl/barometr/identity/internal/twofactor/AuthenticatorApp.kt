package pl.barometr.identity.internal.twofactor

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

/**
 * The phone, as a test can hold one.
 *
 * A hand-written stand-in for the collaborator on the other side of the second factor:
 * given the secret it was set up with, it produces the six digits an authenticator would
 * show. Written rather than borrowed from the production class on purpose — a test that
 * asked [TotpCodes] for the code and then asked it whether the code matched would be
 * asking one implementation to agree with itself.
 */
object AuthenticatorApp {

    private val generator = TimeBasedOneTimePasswordGenerator()

    fun codeFor(secret: String, at: Instant): String {
        val key = SecretKeySpec(decodeBase32(secret), generator.algorithm)

        return "%06d".format(generator.generateOneTimePassword(key, at))
    }

    /** RFC 4648 base32, which is the encoding an authenticator is given a secret in. */
    private fun decodeBase32(secret: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = mutableListOf<Byte>()
        var buffer = 0
        var bits = 0

        secret.trim().uppercase().forEach { character ->
            buffer = (buffer shl 5) or alphabet.indexOf(character)
            bits += 5
            if (bits >= 8) {
                out += ((buffer shr (bits - 8)) and 0xff).toByte()
                bits -= 8
            }
        }

        return out.toByteArray()
    }
}
