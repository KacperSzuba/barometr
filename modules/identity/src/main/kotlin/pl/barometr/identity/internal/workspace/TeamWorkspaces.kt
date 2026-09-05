package pl.barometr.identity.internal.workspace

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import pl.barometr.shared.Ids
import java.time.Clock
import java.time.Duration

/**
 * An organisation's account: who is in it, in what capacity, and what it insists on.
 *
 * **Every rule that decides who may do what lives here rather than in the endpoints.** A
 * workspace administrator is not an application role, so nothing in the security chain
 * can express it; the check has to be a call, and a check that is a call has to be in one
 * place or it is in none.
 *
 * **A workspace always has an owner.** The last one cannot be removed or demoted — an
 * account nobody can pay for, rename or close is the state this refuses to create, and
 * it is a state two colleagues can otherwise reach by arguing.
 */
@Service
class TeamWorkspaces(
    private val workspaces: Workspaces,
    private val invitations: WorkspaceInvitations,
    private val properties: WorkspaceProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun createWorkspace(owner: UserId, name: String): Workspace {
        val workspace = Workspace(
            id = WorkspaceId(Ids.next()),
            name = name.trim(),
            seats = properties.defaultSeats,
            requireTwoFactor = false,
            sessionIdleTimeout = null,
            createdAt = clock.instant(),
        )

        log.info("Workspace {} created by {}", workspace.id, owner.value)

        return workspaces.create(workspace, owner, clock.instant())
    }

    @Transactional(readOnly = true)
    fun workspacesOf(user: UserId): List<WorkspaceMembership> = workspaces.membershipsOf(user)

    /** The workspace as a member sees it, refused for anybody who is not one. */
    @Transactional(readOnly = true)
    fun readWorkspace(caller: UserId, id: WorkspaceId): Workspace {
        membershipOf(caller, id)

        return workspaces.byId(id) ?: throw UnknownWorkspaceException()
    }

    @Transactional(readOnly = true)
    fun membersOf(caller: UserId, id: WorkspaceId): List<WorkspaceMembership> {
        membershipOf(caller, id)

        return workspaces.membersOf(id)
    }

    /**
     * The two policies an institutional customer asks about, set by whoever administers
     * the workspace.
     *
     * Turning the second factor on does not sign anybody out: the people who have not
     * enrolled are let in and can reach nothing but the enrolment routes, which is what
     * the specification means by "blocks access until it is configured". Signing them
     * out instead would lock out the administrator who has just turned it on.
     */
    @Transactional
    fun setPolicy(caller: UserId, id: WorkspaceId, requireTwoFactor: Boolean, idleTimeout: Duration?): Workspace {
        administrator(caller, id)

        if (!workspaces.updatePolicy(id, requireTwoFactor, idleTimeout)) throw UnknownWorkspaceException()
        log.info("Workspace {} policy: two factor {}, idle timeout {}", id, requireTwoFactor, idleTimeout)

        return workspaces.byId(id) ?: throw UnknownWorkspaceException()
    }

    /** Buying more, or giving some back — refused below what is already in use. */
    @Transactional
    fun setSeats(caller: UserId, id: WorkspaceId, seats: Int): Workspace {
        administrator(caller, id)
        if (seats < taken(id)) throw NoSeatsLeftException()

        if (!workspaces.updateSeats(id, seats)) throw UnknownWorkspaceException()

        return workspaces.byId(id) ?: throw UnknownWorkspaceException()
    }

    @Transactional
    fun changeRole(caller: UserId, id: WorkspaceId, member: UserId, role: WorkspaceRole) {
        administrator(caller, id)
        val current = workspaces.membership(id, member) ?: throw UnknownWorkspaceException()

        if (current.role == WorkspaceRole.OWNER && role != WorkspaceRole.OWNER && workspaces.countOwners(id) == 1) {
            throw LastOwnerException()
        }

        workspaces.changeRole(id, member, role)
    }

    /**
     * Removes somebody, freeing the seat.
     *
     * Somebody may always remove themselves — leaving a workspace is not a favour an
     * administrator grants — unless they are its last owner, which is the one case where
     * leaving would strand the account.
     */
    @Transactional
    fun removeMember(caller: UserId, id: WorkspaceId, member: UserId) {
        if (caller != member) administrator(caller, id) else membershipOf(caller, id)

        val membership = workspaces.membership(id, member) ?: throw UnknownWorkspaceException()
        if (membership.role == WorkspaceRole.OWNER && workspaces.countOwners(id) == 1) throw LastOwnerException()

        workspaces.removeMember(id, member)
        log.info("{} removed from workspace {} by {}", member.value, id, caller.value)
    }

    /** Members plus the invitations still open: a seat offered is a seat spoken for. */
    @Transactional(readOnly = true)
    fun taken(id: WorkspaceId): Int = workspaces.countMembers(id) + invitations.countOpenIn(id)

    /** The caller's membership, or the workspace reported as absent. */
    fun membershipOf(caller: UserId, id: WorkspaceId): WorkspaceMembership =
        workspaces.membership(id, caller) ?: throw UnknownWorkspaceException()

    /** The caller's membership, refused unless it administers the workspace. */
    fun administrator(caller: UserId, id: WorkspaceId): WorkspaceMembership =
        membershipOf(caller, id).takeIf { it.role.administers } ?: throw NotWorkspaceAdministratorException()
}
