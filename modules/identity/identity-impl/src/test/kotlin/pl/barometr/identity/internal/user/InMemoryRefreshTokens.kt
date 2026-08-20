package pl.barometr.identity.internal.user

import java.time.Instant
import java.util.UUID

/**
 * [RefreshTokens] in a map.
 *
 * The row lock the real implementation takes has no equivalent here, and does not
 * need one: these tests drive the calls in the order the lock would have produced.
 * What is being checked is the policy — used, expired, revoked, inside or outside
 * the grace window — not the database's willingness to serialise two callers.
 */
class InMemoryRefreshTokens : RefreshTokens {

    private val stored = mutableListOf<RefreshTokenEntity>()

    val all: List<RefreshTokenEntity> get() = stored.toList()

    fun live(): List<RefreshTokenEntity> = stored.filter { it.revokedAt == null }

    override fun byTokenHashForUpdate(hash: String): RefreshTokenEntity? =
        stored.firstOrNull { it.tokenHash == hash }

    override fun add(token: RefreshTokenEntity): RefreshTokenEntity {
        stored += token
        return token
    }

    override fun markUsed(id: UUID, at: Instant) {
        stored.firstOrNull { it.id == id && it.usedAt == null }?.usedAt = at
    }

    override fun revokeFamily(familyId: UUID, at: Instant): Int =
        stored.filter { it.familyId == familyId && it.revokedAt == null }
            .onEach { it.revokedAt = at }
            .size
}
