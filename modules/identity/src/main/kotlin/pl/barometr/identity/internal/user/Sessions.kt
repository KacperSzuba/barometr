package pl.barometr.identity.internal.user

import java.time.Instant
import java.util.UUID

/**
 * What the session list needs from storage.
 *
 * Narrow on purpose, like [RefreshTokens] beside it: every method is an operation the
 * service performs rather than a query it happens to want, so the sequence stays
 * explicit and survives a change of persistence.
 */
interface Sessions {

    fun open(session: SignedInSession): SignedInSession

    /** Null for a family nothing opened — every login before this table existed. */
    fun byFamily(familyId: UUID): SignedInSession?

    /** Moves the last-seen mark, and the address with it: a device does travel. */
    fun markSeen(familyId: UUID, at: Instant, clientIp: String?)

    /** @return false when there was nothing live to revoke. */
    fun revoke(familyId: UUID, at: Instant): Boolean

    /** Every live session of this account except one, which is the caller's own. */
    fun revokeAllExcept(userId: UUID, keep: UUID, at: Instant): List<UUID>

    fun liveFor(userId: UUID): List<SignedInSession>

    /** Every session this account has ever had, revoked ones included. */
    fun countFor(userId: UUID): Int

    /**
     * How many of them came from a client calling itself this.
     *
     * Not a device fingerprint and not sold as one: it is the only thing a browser
     * offers, and "we have never seen this before" is a question it can answer well
     * enough to be worth asking.
     */
    fun countWithUserAgent(userId: UUID, userAgent: String): Int
}
