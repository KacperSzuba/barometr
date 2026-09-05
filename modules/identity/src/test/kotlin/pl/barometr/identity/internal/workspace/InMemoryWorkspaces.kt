package pl.barometr.identity.internal.workspace

import pl.barometr.identity.api.UserId
import java.time.Duration
import java.time.Instant

/**
 * [Workspaces] in two maps.
 *
 * The two policy reads at the bottom are answered the way the real queries answer them —
 * strictest wins — because that rule is the point of the port and a fake that summed or
 * averaged would let a wrong test pass.
 */
class InMemoryWorkspaces : Workspaces {
    private val stored = mutableMapOf<WorkspaceId, Workspace>()
    private val members = mutableListOf<WorkspaceMembership>()

    override fun create(workspace: Workspace, owner: UserId, at: Instant): Workspace {
        stored[workspace.id] = workspace
        members += WorkspaceMembership(workspace.id, owner, WorkspaceRole.OWNER, at)
        return workspace
    }

    override fun byId(id: WorkspaceId): Workspace? = stored[id]

    override fun updatePolicy(id: WorkspaceId, requireTwoFactor: Boolean, idleTimeout: Duration?): Boolean {
        val workspace = stored[id] ?: return false
        stored[id] = workspace.copy(requireTwoFactor = requireTwoFactor, sessionIdleTimeout = idleTimeout)
        return true
    }

    override fun updateSeats(id: WorkspaceId, seats: Int): Boolean {
        val workspace = stored[id] ?: return false
        stored[id] = workspace.copy(seats = seats)
        return true
    }

    override fun membershipsOf(user: UserId): List<WorkspaceMembership> =
        members.filter { it.user == user }.sortedBy { it.joinedAt }

    override fun membersOf(id: WorkspaceId): List<WorkspaceMembership> =
        members.filter { it.workspace == id }.sortedBy { it.joinedAt }

    override fun membership(id: WorkspaceId, user: UserId): WorkspaceMembership? =
        members.firstOrNull { it.workspace == id && it.user == user }

    override fun addMember(membership: WorkspaceMembership): Boolean {
        if (membership(membership.workspace, membership.user) != null) return false
        members += membership
        return true
    }

    override fun changeRole(id: WorkspaceId, user: UserId, role: WorkspaceRole): Boolean {
        val existing = membership(id, user) ?: return false
        members.remove(existing)
        members += existing.copy(role = role)
        return true
    }

    override fun removeMember(id: WorkspaceId, user: UserId): Boolean =
        members.remove(membership(id, user) ?: return false)

    override fun countMembers(id: WorkspaceId): Int = membersOf(id).size

    override fun countOwners(id: WorkspaceId): Int = membersOf(id).count { it.role == WorkspaceRole.OWNER }

    override fun anyRequiresTwoFactor(user: UserId): Boolean =
        membershipsOf(user).any { stored[it.workspace]?.requireTwoFactor == true }

    override fun strictestIdleTimeout(user: UserId): Duration? =
        membershipsOf(user).mapNotNull { stored[it.workspace]?.sessionIdleTimeout }.minOrNull()
}
