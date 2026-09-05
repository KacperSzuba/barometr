package pl.barometr.identity.internal.workspace

import pl.barometr.identity.api.UserId
import java.time.Instant
import java.util.UUID

/**
 * A seat somebody has been offered.
 *
 * It holds an address rather than an account: most invitations go to people who have not
 * registered yet, and the whole point of the flow is that they can. The token itself is
 * not here — only its hash is stored, and the plaintext lives exactly as long as it takes
 * to put it in a link.
 */
data class PendingInvitation(
    val id: UUID,
    val workspace: WorkspaceId,
    val email: String,
    val role: WorkspaceRole,
    val tokenHash: String,
    val invitedBy: UserId,
    val createdAt: Instant,
    val expiresAt: Instant,
    val acceptedAt: Instant?,
    val revokedAt: Instant?,
)
