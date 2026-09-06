package pl.barometr.identity.internal.workspace

import pl.barometr.identity.api.UserId
import java.time.Instant

/**
 * A member of a workspace, with something to call them by.
 *
 * A membership on its own is three identifiers, which is enough to decide what somebody
 * may do and not enough to render a list anybody can check: an administrator looking at
 * a page of UUIDs cannot tell which row is the colleague who left.
 *
 * [email] is null for a membership whose account is gone. Not an impossible state and
 * not an error either — closing an account is a route this system implements, and a row
 * that outlived one is a row somebody should be able to see and remove.
 */
data class NamedMember(
    val user: UserId,
    val email: String?,
    val role: WorkspaceRole,
    val joinedAt: Instant,
)
