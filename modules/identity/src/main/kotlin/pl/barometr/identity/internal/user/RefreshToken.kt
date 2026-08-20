package pl.barometr.identity.internal.user

import java.time.Instant
import java.util.UUID

/**
 * A refresh token as stored — never the token itself, only its SHA-256.
 *
 * [usedAt] is when the token was first presented, which is what opens the grace
 * window; [revokedAt] is set for every token in a family at once when a login ends
 * or a replay is detected.
 */
data class RefreshToken(
    val id: UUID,
    val userId: UUID,
    val tokenHash: String,
    /** Shared by every token descending from one login; revoked as a unit on replay. */
    val familyId: UUID,
    /**
     * The token this one replaced. Lineage for audit; a token may have several
     * successors when parallel refreshes land inside the grace window.
     */
    val predecessorId: UUID? = null,
    val expiresAt: Instant,
    val usedAt: Instant? = null,
    val revokedAt: Instant? = null,
    val createdAt: Instant,
)
