package pl.barometr.identity.internal.user

import java.time.Instant
import java.util.UUID

/**
 * [Sessions] in a map.
 *
 * Rows are values here, as they are in the database: a change is a replacement, so a
 * test cannot accidentally mutate a session it is also holding a reference to and
 * conclude that the code did it.
 */
class InMemorySessions : Sessions {

    private val stored = mutableMapOf<UUID, SignedInSession>()

    val all: List<SignedInSession> get() = stored.values.toList()

    override fun open(session: SignedInSession): SignedInSession {
        // The upsert the real one performs: one family is one session, however many
        // tokens descend from it.
        stored[session.familyId] = stored[session.familyId]?.copy(lastSeenAt = session.lastSeenAt) ?: session
        return stored.getValue(session.familyId)
    }

    override fun byFamily(familyId: UUID): SignedInSession? = stored[familyId]

    override fun markSeen(familyId: UUID, at: Instant, clientIp: String?) {
        stored[familyId]?.takeIf { it.revokedAt == null }?.let { session ->
            stored[familyId] = session.copy(lastSeenAt = at, clientIp = clientIp ?: session.clientIp)
        }
    }

    override fun revoke(familyId: UUID, at: Instant): Boolean {
        val live = stored[familyId]?.takeIf { it.revokedAt == null } ?: return false
        stored[familyId] = live.copy(revokedAt = at)
        return true
    }

    override fun revokeAllExcept(userId: UUID, keep: UUID, at: Instant): List<UUID> =
        stored.values
            .filter { it.userId == userId && it.familyId != keep && it.revokedAt == null }
            .map { it.familyId }
            .onEach { revoke(it, at) }

    override fun countFor(userId: UUID): Int = stored.values.count { it.userId == userId }

    override fun countWithUserAgent(userId: UUID, userAgent: String): Int =
        stored.values.count { it.userId == userId && it.userAgent == userAgent }

    override fun liveFor(userId: UUID): List<SignedInSession> =
        stored.values
            .filter { it.userId == userId && it.revokedAt == null }
            .sortedByDescending { it.lastSeenAt }
}
