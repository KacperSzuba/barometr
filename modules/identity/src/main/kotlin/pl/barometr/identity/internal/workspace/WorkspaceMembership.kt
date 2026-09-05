package pl.barometr.identity.internal.workspace

import pl.barometr.identity.api.UserId
import java.time.Instant

/** Somebody's place in a workspace: which one, and in what capacity. */
data class WorkspaceMembership(
    val workspace: WorkspaceId,
    val user: UserId,
    val role: WorkspaceRole,
    val joinedAt: Instant,
)
