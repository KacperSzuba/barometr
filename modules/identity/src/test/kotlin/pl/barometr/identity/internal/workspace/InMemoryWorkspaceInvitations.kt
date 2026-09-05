package pl.barometr.identity.internal.workspace

import java.time.Instant
import java.util.UUID

/**
 * [WorkspaceInvitations] in a map.
 *
 * Expiry and settlement are applied on read, exactly as the real queries apply them: an
 * invitation that has run out or been taken must not be found at all, so that no path
 * above can forget to look.
 */
class InMemoryWorkspaceInvitations : WorkspaceInvitations {
    private val stored = mutableMapOf<UUID, PendingInvitation>()

    override fun issue(invitation: PendingInvitation): PendingInvitation {
        stored[invitation.id] = invitation
        return invitation
    }

    override fun byTokenHash(hash: String, now: Instant): PendingInvitation? =
        stored.values.firstOrNull {
            it.tokenHash == hash && it.acceptedAt == null && it.revokedAt == null && it.expiresAt.isAfter(now)
        }

    override fun openIn(workspace: WorkspaceId): List<PendingInvitation> =
        stored.values
            .filter { it.workspace == workspace && it.acceptedAt == null && it.revokedAt == null }
            .sortedBy { it.createdAt }

    override fun countOpenIn(workspace: WorkspaceId): Int = openIn(workspace).size

    override fun accept(id: UUID, at: Instant): Boolean {
        val open = stored[id]?.takeIf { it.acceptedAt == null && it.revokedAt == null } ?: return false
        stored[id] = open.copy(acceptedAt = at)
        return true
    }

    override fun revoke(workspace: WorkspaceId, id: UUID, at: Instant): Boolean {
        val open = stored[id]
            ?.takeIf { it.workspace == workspace && it.acceptedAt == null && it.revokedAt == null }
            ?: return false
        stored[id] = open.copy(revokedAt = at)
        return true
    }
}
