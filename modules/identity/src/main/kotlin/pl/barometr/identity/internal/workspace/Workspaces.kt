package pl.barometr.identity.internal.workspace

import pl.barometr.identity.api.UserId
import java.time.Duration
import java.time.Instant

/**
 * Where workspaces and their members live.
 *
 * The two reads at the bottom are the enforcement questions, and they are queries rather
 * than a walk over memberships because they run on every sign-in and every refresh: what
 * a person's workspaces insist on has to cost one indexed statement.
 */
interface Workspaces {

    fun create(workspace: Workspace, owner: UserId, at: Instant): Workspace

    fun byId(id: WorkspaceId): Workspace?

    fun updatePolicy(id: WorkspaceId, requireTwoFactor: Boolean, idleTimeout: Duration?): Boolean

    fun updateSeats(id: WorkspaceId, seats: Int): Boolean

    fun membershipsOf(user: UserId): List<WorkspaceMembership>

    fun membersOf(id: WorkspaceId): List<WorkspaceMembership>

    fun membership(id: WorkspaceId, user: UserId): WorkspaceMembership?

    fun addMember(membership: WorkspaceMembership): Boolean

    fun changeRole(id: WorkspaceId, user: UserId, role: WorkspaceRole): Boolean

    fun removeMember(id: WorkspaceId, user: UserId): Boolean

    fun countMembers(id: WorkspaceId): Int

    fun countOwners(id: WorkspaceId): Int

    /** True when any workspace this account belongs to insists on a second factor. */
    fun anyRequiresTwoFactor(user: UserId): Boolean

    /**
     * The shortest idle timeout any of this account's workspaces has chosen, or null
     * when none has chosen one.
     *
     * The shortest rather than the newest: somebody in two workspaces is subject to both
     * policies, and the one that ends a session sooner is the one that means anything.
     */
    fun strictestIdleTimeout(user: UserId): Duration?
}
