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

    private val stored = mutableListOf<RefreshToken>()

    val all: List<RefreshToken> get() = stored.toList()

    fun live(): List<RefreshToken> = stored.filter { it.revokedAt == null }

    override fun byTokenHashForUpdate(hash: String): RefreshToken? =
        stored.firstOrNull { it.tokenHash == hash }

    override fun add(token: RefreshToken): RefreshToken {
        stored += token
        return token
    }

    override fun markUsed(id: UUID, at: Instant) {
        replaceWhere({ it.id == id && it.usedAt == null }) { it.copy(usedAt = at) }
    }

    override fun revokeFamily(familyId: UUID, at: Instant): Int =
        replaceWhere({ it.familyId == familyId && it.revokedAt == null }) { it.copy(revokedAt = at) }

    /** Rows are values, so a change is a replacement — as it is in the database. */
    private fun replaceWhere(
        matches: (RefreshToken) -> Boolean,
        change: (RefreshToken) -> RefreshToken,
    ): Int {
        var changed = 0
        stored.replaceAll { token ->
            if (matches(token)) {
                changed++
                change(token)
            } else {
                token
            }
        }
        return changed
    }
}
