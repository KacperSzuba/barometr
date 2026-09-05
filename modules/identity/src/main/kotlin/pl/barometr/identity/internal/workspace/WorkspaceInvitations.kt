package pl.barometr.identity.internal.workspace

import java.time.Instant
import java.util.UUID

/** Where the seats that have been offered and not yet taken are kept. */
interface WorkspaceInvitations {

    fun issue(invitation: PendingInvitation): PendingInvitation

    /** The open, unexpired invitation this token names, or null when it names none. */
    fun byTokenHash(hash: String, now: Instant): PendingInvitation?

    fun openIn(workspace: WorkspaceId): List<PendingInvitation>

    fun countOpenIn(workspace: WorkspaceId): Int

    /** @return false when it was already accepted, revoked or gone. */
    fun accept(id: UUID, at: Instant): Boolean

    fun revoke(workspace: WorkspaceId, id: UUID, at: Instant): Boolean
}
