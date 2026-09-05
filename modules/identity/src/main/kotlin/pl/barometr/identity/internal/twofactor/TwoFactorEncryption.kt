package pl.barometr.identity.internal.twofactor

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.encrypt.Encryptors
import org.springframework.security.crypto.encrypt.TextEncryptor

/**
 * How a TOTP secret is written down.
 *
 * `Encryptors.delux` is AES-256 in GCM with a PBKDF2-derived key — Spring Security's
 * own, rather than a `Cipher` assembled here: choosing a mode, a nonce policy and a tag
 * length correctly is not work this codebase should be doing, and getting any of the
 * three wrong is silent.
 *
 * A deployment with no key configured gets an encryptor that refuses, rather than one
 * that quietly stores secrets in the clear. The refusal surfaces the first time somebody
 * tries to enrol, which is when there is still nothing to lose.
 */
@Configuration
class TwoFactorEncryption {

    @Bean
    fun totpSecretEncryptor(properties: TwoFactorProperties): TextEncryptor =
        if (properties.encryptionKey.isBlank() || properties.encryptionSalt.isBlank()) {
            RefusingEncryptor
        } else {
            Encryptors.delux(properties.encryptionKey, properties.encryptionSalt)
        }

    /**
     * What an unconfigured deployment gets: something that says so on use.
     *
     * Not `Encryptors.noOpText()`, which would store every second factor in plain text
     * and report success — the failure mode nobody notices until a dump appears.
     */
    private object RefusingEncryptor : TextEncryptor {
        override fun encrypt(text: String): String = error("app.identity.two-factor.encryption-key is not set")

        override fun decrypt(encryptedText: String): String =
            error("app.identity.two-factor.encryption-key is not set")
    }
}
