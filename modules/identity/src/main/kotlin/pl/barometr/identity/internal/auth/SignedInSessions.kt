package pl.barometr.identity.internal.auth

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.SignedInFromNewDevice
import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.UserSessionsRevoked
import pl.barometr.identity.internal.user.ApproximateLocations
import pl.barometr.identity.internal.user.RefreshTokens
import pl.barometr.identity.internal.user.SignedInSession
import pl.barometr.identity.internal.user.Sessions
import java.time.Clock
import java.util.UUID

/**
 * The devices an account is signed in on, and ending any of them.
 *
 * A session is a refresh-token family: one login issues one family, and every token
 * that descends from it belongs to the same device. Ending a session is therefore the
 * operation replay detection already performs — revoke the family — with a row beside it
 * saying what the family was, so a person can recognise which device they are ending.
 *
 * **What "immediately" means here, said plainly.** Revoking a session kills every
 * refresh token of that login at once, on every instance, because the revocation is a
 * row in the database rather than state in a process. The access token already issued
 * keeps working until it expires — fifteen minutes at most — because this system has no
 * revocation list for access tokens, which is the deliberate trade that keeps every
 * request from becoming a database read. Anything that must stop sooner than that is a
 * password change, and that is a different operation.
 */
@Service
class SignedInSessions(
    private val sessions: Sessions,
    private val locations: ApproximateLocations,
    private val tokens: RefreshTokens,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Records the device a login was made from, or restates the one this family has —
     * and says so when the device is one this account has not used before.
     */
    @Transactional
    fun openSession(userId: UUID, familyId: UUID, from: ClientFingerprint): SignedInSession {
        val now = clock.instant()

        val opened = sessions.open(
            SignedInSession(
                familyId = familyId,
                userId = userId,
                userAgent = from.userAgent,
                clientIp = from.clientIp,
                createdAt = now,
                lastSeenAt = now,
            ),
        )

        if (isNewDevice(userId, from)) {
            events.publishEvent(
                SignedInFromNewDevice(
                    userId = UserId(userId),
                    sessionId = familyId,
                    userAgent = from.userAgent,
                    clientIp = from.clientIp,
                    approximateLocation = locations.locate(from.clientIp),
                    occurredAt = now,
                ),
            )
        }

        return opened
    }

    /**
     * Whether this is a device the account has not been signed in on before.
     *
     * Two conditions, and both matter. The first sign-in an account ever makes is not
     * news — it is the person who has just registered, and a message telling them they
     * signed in is the kind of mail that teaches people to ignore this one. And a client
     * that sent no user agent cannot be told from any other: with nothing to compare,
     * every sign-in would look new, which would turn the warning into noise within a
     * week.
     *
     * What this is not is a device fingerprint. Two colleagues on the same build of the
     * same browser look alike to it, and somebody who upgrades theirs looks new. It errs
     * towards telling people, which is the right direction for the one message that
     * might be about a stolen password.
     */
    private fun isNewDevice(userId: UUID, from: ClientFingerprint): Boolean {
        val userAgent = from.userAgent ?: return false

        return sessions.countFor(userId) > 1 && sessions.countWithUserAgent(userId, userAgent) == 1
    }

    @Transactional(readOnly = true)
    fun sessionsOf(userId: UserId): List<SignedInSession> = sessions.liveFor(userId.value)

    /** Roughly where a session's address is, for the list that shows it. */
    fun locationOf(session: SignedInSession): String? = locations.locate(session.clientIp)

    /**
     * Ends one session of this account.
     *
     * A session that is not the caller's is reported as absent rather than forbidden:
     * confirming that a family identifier exists is an answer nobody is owed, and the
     * caller can do nothing different with either reply.
     */
    @Transactional
    fun endSession(owner: UserId, familyId: UUID) {
        val session = sessions.byFamily(familyId)?.takeIf { it.userId == owner.value && it.revokedAt == null }
            ?: throw UnknownSessionException(familyId.toString())

        revoke(owner, listOf(session.familyId), UserSessionsRevoked.RevocationReason.REMOTE_LOGOUT)
    }

    /**
     * Ends every other session — "sign out everywhere else", which is what somebody
     * reaches for after seeing a device they do not recognise.
     *
     * The caller's own session is kept, deliberately: signing somebody out of the tab
     * they are looking at, in the middle of securing their account, is the worst moment
     * to make them log in again.
     */
    @Transactional
    fun endEverySessionExcept(owner: UserId, keep: UUID): Int {
        val ended = sessions.revokeAllExcept(owner.value, keep, clock.instant())
        if (ended.isNotEmpty()) revoke(owner, ended, UserSessionsRevoked.RevocationReason.REMOTE_LOGOUT)

        return ended.size
    }

    private fun revoke(owner: UserId, families: List<UUID>, reason: UserSessionsRevoked.RevocationReason) {
        val now = clock.instant()
        families.forEach { family ->
            sessions.revoke(family, now)
            tokens.revokeFamily(family, now)
        }

        log.info("{} session(s) of {} ended: {}", families.size, owner.value, reason)
        events.publishEvent(UserSessionsRevoked(owner, reason, now))
    }
}
