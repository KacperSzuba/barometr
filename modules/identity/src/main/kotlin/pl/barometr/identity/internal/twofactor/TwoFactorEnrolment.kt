package pl.barometr.identity.internal.twofactor

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/**
 * Turning a second factor on, off, and back on after a lost phone.
 *
 * **Setting up and turning on are two steps.** Somebody scans a QR image and then proves
 * they can read a code from it; only the second step changes how they sign in. Enabling
 * a factor that the person cannot actually produce is how an account is lost, and it is
 * the single most common way this feature is got wrong.
 *
 * **Recovery codes are shown once and stored as hashes.** They are the answer to a phone
 * that has been dropped in a river, and they are also a password of last resort — so
 * they are eighty bits each, hashed the way a refresh token is, and minting a new set
 * retires the old one.
 */
@Service
class TwoFactorEnrolment(
    private val secrets: TwoFactorSecrets,
    private val recoveryCodes: RecoveryCodes,
    private val trust: DeviceTrust,
    private val codes: TotpCodes,
    private val properties: TwoFactorProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    /**
     * Starts enrolment, replacing any unfinished attempt.
     *
     * Refused for somebody who already has a confirmed factor: turning it off is a
     * deliberate act with its own route, and silently replacing a working authenticator
     * because a stale tab posted here again is not a thing to leave possible.
     */
    @Transactional
    fun beginEnrolment(user: UserId, email: String): TotpSetup {
        if (secrets.forUser(user.value)?.isConfirmed == true) throw TwoFactorAlreadyEnabledException()

        val secret = codes.newSecret()
        secrets.save(EnrolledSecret(user.value, secret, confirmedAt = null, createdAt = clock.instant()))

        return TotpSetup(secret, codes.setupUri(secret, email))
    }

    /**
     * Finishes enrolment against a code from the authenticator, and mints the recovery
     * codes — the only time they exist anywhere readable.
     */
    @Transactional
    fun confirmEnrolment(user: UserId, code: String): List<String> {
        val enrolled = secrets.forUser(user.value) ?: throw TwoFactorNotEnabledException()
        if (enrolled.isConfirmed) throw TwoFactorAlreadyEnabledException()
        if (!codes.matches(enrolled.secret, code)) throw InvalidTwoFactorCodeException()

        secrets.confirm(user.value, clock.instant())
        log.info("Second factor enabled for {}", user.value)

        return mintRecoveryCodes(user)
    }

    /** A new set, for somebody who has used most of theirs. The old set stops working. */
    @Transactional
    fun mintRecoveryCodes(user: UserId): List<String> {
        val minted = List(properties.recoveryCodes) { recoveryCode() }
        recoveryCodes.replaceAll(user.value, minted.map(::hash), clock.instant())

        return minted
    }

    @Transactional(readOnly = true)
    fun statusOf(user: UserId): TwoFactorStatus {
        val enrolled = secrets.forUser(user.value)

        return TwoFactorStatus(
            enabled = enrolled?.isConfirmed == true,
            enrolmentStarted = enrolled != null,
            recoveryCodesLeft = if (enrolled?.isConfirmed == true) recoveryCodes.unusedCount(user.value) else 0,
        )
    }

    /**
     * Turns it off against a code the caller can still produce.
     *
     * Somebody who is signed in is not necessarily the account's owner — an unlocked
     * laptop is the case this guards — so removing the second factor takes the second
     * factor. A recovery code counts, and is spent doing it.
     */
    @Transactional
    fun confirmDisable(user: UserId, code: String) {
        val enrolled = secrets.forUser(user.value)?.takeIf { it.isConfirmed } ?: throw TwoFactorNotEnabledException()

        val accepted = codes.matches(enrolled.secret, code) ||
            recoveryCodes.consume(user.value, hash(code), clock.instant())
        if (!accepted) throw InvalidTwoFactorCodeException()

        disable(user)
    }

    /**
     * Turns it off, taking the recovery codes with it.
     *
     * Leaving them behind would leave ten passwords of last resort lying around for an
     * account that no longer has a second factor at all.
     *
     * Called by [confirmDisable] for the account's own owner, and by the operator route
     * for somebody who has lost both their phone and their codes — which is why it takes
     * no proof of its own and is not reachable from HTTP without one of those two.
     *
     * It also forgets every remembered device, and that is not tidiness: a device
     * trusted to skip a factor that has just been removed would be a way in with the
     * password alone, granted by the act of removing the protection.
     */
    @Transactional
    fun disable(user: UserId) {
        if (!secrets.delete(user.value)) throw TwoFactorNotEnabledException()

        recoveryCodes.deleteAll(user.value)
        // And the devices allowed to skip it: trust in a factor that no longer exists is
        // a password-only sign-in nobody asked for.
        val forgotten = trust.forgetEveryDevice(user)

        log.info("Second factor disabled for {}; {} trusted device(s) forgotten", user.value, forgotten)
    }

    /**
     * Codes are read aloud over the phone and typed by somebody who is already having a
     * bad day: no vowels, so no word can be formed, and no `0`, `1`, `8` or `B` to be
     * confused with `O`, `I` or one another. Sixteen characters of that alphabet is
     * about eighty bits, which is why the hash below needs no work factor.
     */
    private fun recoveryCode(): String =
        (1..RECOVERY_CODE_LENGTH)
            .map { RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)] }
            .chunked(RECOVERY_GROUP) { group -> group.joinToString("") }
            .joinToString("-")

    /**
     * Plain SHA-256, for the reason the refresh tokens give: the input is already
     * eighty bits of entropy chosen by us, not a password chosen by a person, so a work
     * factor would cost every verification and buy nothing.
     */
    private fun hash(code: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(code.replace("-", "").uppercase().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    /** What a code hashes to, for whoever is checking one. */
    fun hashOf(code: String): String = hash(code)

    private companion object {
        const val RECOVERY_ALPHABET = "CDFGHJKLMNPQRSTVWXYZ23456789"
        const val RECOVERY_CODE_LENGTH = 16
        const val RECOVERY_GROUP = 4
    }
}
