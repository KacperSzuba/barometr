package pl.barometr.identity.internal.twofactor

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.shared.Ids
import java.time.Clock
import java.util.UUID

/**
 * The second half of signing in: the gap between a proved password and a code.
 *
 * **The challenge grants nothing.** It is an identifier that says "somebody knew this
 * account's password five minutes ago" and can be exchanged for tokens only together
 * with a second factor. Anything else — a short-lived access token, a cookie — would
 * make the second factor optional for whoever holds the first.
 *
 * **Attempts are counted and the count survives.** Six digits is a million guesses, so a
 * challenge that could be answered indefinitely would be a second factor in name only.
 * The counter is a column rather than a field, because the next attempt may be answered
 * by another instance.
 *
 * **A recovery code is checked here too, and looks identical when wrong.** Which of the
 * two kinds of code was presented is not something the answer should reveal.
 */
@Service
class TwoFactorSignIn(
    private val secrets: TwoFactorSecrets,
    private val recoveryCodes: RecoveryCodes,
    private val challenges: LoginChallenges,
    private val codes: TotpCodes,
    private val enrolment: TwoFactorEnrolment,
    private val properties: TwoFactorProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun isRequiredFor(userId: UUID): Boolean = secrets.forUser(userId)?.isConfirmed == true

    @Transactional
    fun challengeFor(userId: UUID): LoginChallenge {
        val now = clock.instant()

        return challenges.open(
            LoginChallenge(
                id = Ids.next(),
                userId = userId,
                expiresAt = now.plus(properties.challengeTtl),
                consumedAt = null,
                attempts = 0,
                createdAt = now,
            ),
        )
    }

    /**
     * Answers a challenge, and returns whose it was.
     *
     * `noRollbackFor` is what makes the attempt counter mean anything: a wrong code
     * throws, and a plain transaction would roll the increment back with it, leaving a
     * challenge that can be guessed at forever. The exception still propagates; only the
     * rollback is suppressed — the same trade refresh rotation makes when it revokes a
     * stolen family and then throws.
     */
    @Transactional(noRollbackFor = [InvalidTwoFactorCodeException::class])
    fun answerChallenge(challengeId: UUID, code: String): UUID {
        val now = clock.instant()
        val challenge = challenges.byIdForUpdate(challengeId) ?: throw InvalidTwoFactorCodeException()

        if (challenge.consumedAt != null || challenge.expiresAt.isBefore(now)) {
            throw InvalidTwoFactorCodeException()
        }
        if (challenges.recordAttempt(challenge.id) > properties.maxAttempts) {
            // Spent rather than merely refused: leaving it open would let the next
            // attempt start the count again from wherever it stopped.
            challenges.consume(challenge.id, now)
            log.warn("Second-factor challenge for {} spent on attempts", challenge.userId)
            throw InvalidTwoFactorCodeException()
        }

        if (!accepts(challenge.userId, code, now)) throw InvalidTwoFactorCodeException()

        challenges.consume(challenge.id, now)

        return challenge.userId
    }

    /**
     * The authenticator's code, or one of the ten written down in a drawer.
     *
     * The recovery code is tried second and only when the first fails, so an
     * authenticator that works never spends one.
     */
    private fun accepts(userId: UUID, code: String, now: java.time.Instant): Boolean {
        val secret = secrets.forUser(userId)?.takeIf { it.isConfirmed } ?: return false

        if (codes.matches(secret.secret, code)) return true

        val spent = recoveryCodes.consume(userId, enrolment.hashOf(code), now)
        if (spent) log.info("Recovery code used for {}; {} left", userId, recoveryCodes.unusedCount(userId))

        return spent
    }
}
