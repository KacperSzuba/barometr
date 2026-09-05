package pl.barometr.identity.internal.workspace

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.callerOf
import java.security.Principal
import java.time.Duration
import java.util.UUID

/**
 * An organisation's account: its people, its seats, and the two things it may insist on.
 *
 * Authorisation here is not the security chain's: "administers this workspace" is not an
 * application role and no annotation can express it. Every route therefore goes through
 * [TeamWorkspaces], which is where the rule lives — one place, or none.
 */
@RestController
@RequestMapping("/api/v1/workspaces")
class WorkspaceController(private val workspaces: TeamWorkspaces) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(caller: Principal, @Valid @RequestBody request: CreateRequest): WorkspaceResponse =
        describe(workspaces.createWorkspace(callerOf(caller), request.name), WorkspaceRole.OWNER)

    @GetMapping
    fun mine(caller: Principal): List<MembershipResponse> =
        workspaces.workspacesOf(callerOf(caller)).map {
            MembershipResponse(it.workspace.value, it.role.wireName, it.joinedAt.toString())
        }

    @GetMapping("/{id}")
    fun read(caller: Principal, @PathVariable id: UUID): WorkspaceResponse {
        val user = callerOf(caller)
        val workspace = WorkspaceId(id)

        return describe(workspaces.readWorkspace(user, workspace), workspaces.membershipOf(user, workspace).role)
    }

    @GetMapping("/{id}/members")
    fun members(caller: Principal, @PathVariable id: UUID): List<MemberResponse> =
        workspaces.membersOf(callerOf(caller), WorkspaceId(id)).map {
            MemberResponse(it.user.value, it.role.wireName, it.joinedAt.toString())
        }

    @PutMapping("/{id}/members/{userId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changeRole(
        caller: Principal,
        @PathVariable id: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: RoleRequest,
    ) {
        workspaces.changeRole(callerOf(caller), WorkspaceId(id), UserId(userId), roleOf(request.role))
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeMember(caller: Principal, @PathVariable id: UUID, @PathVariable userId: UUID) {
        workspaces.removeMember(callerOf(caller), WorkspaceId(id), UserId(userId))
    }

    /**
     * The policies, and the only two an institutional customer asks about before signing:
     * a second factor everybody must have, and sessions that end sooner than the
     * deployment's default.
     */
    @PutMapping("/{id}/policy")
    fun setPolicy(
        caller: Principal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: PolicyRequest,
    ): WorkspaceResponse {
        val user = callerOf(caller)
        val workspace = WorkspaceId(id)
        val updated = workspaces.setPolicy(
            caller = user,
            id = workspace,
            requireTwoFactor = request.requireTwoFactor,
            idleTimeout = request.sessionIdleTimeout?.let(Duration::parse),
        )

        return describe(updated, workspaces.membershipOf(user, workspace).role)
    }

    @PutMapping("/{id}/seats")
    fun setSeats(
        caller: Principal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: SeatsRequest,
    ): WorkspaceResponse {
        val user = callerOf(caller)
        val workspace = WorkspaceId(id)

        return describe(workspaces.setSeats(user, workspace, request.seats), workspaces.membershipOf(user, workspace).role)
    }

    /** A role outside the vocabulary is a caller's mistake, not an impossible state. */
    private fun roleOf(wireName: String): WorkspaceRole =
        WorkspaceRole.of(wireName) ?: throw UnknownWorkspaceRoleException(wireName)

    private fun describe(workspace: Workspace, mine: WorkspaceRole) = WorkspaceResponse(
        id = workspace.id.value,
        name = workspace.name,
        seats = workspace.seats,
        seatsTaken = workspaces.taken(workspace.id),
        requireTwoFactor = workspace.requireTwoFactor,
        sessionIdleTimeout = workspace.sessionIdleTimeout?.toString(),
        myRole = mine.wireName,
    )

    data class CreateRequest(
        @field:NotBlank
        @field:Size(max = 120)
        val name: String,
    )

    data class RoleRequest(@field:NotBlank val role: String)

    data class PolicyRequest(
        val requireTwoFactor: Boolean,
        /** ISO-8601, as `PT8H` or `P7D`. Null means "whatever the deployment says". */
        val sessionIdleTimeout: String? = null,
    )

    data class SeatsRequest(
        /**
         * Bounded here as well as by the `CHECK` on the column, so a number nobody could
         * mean is a `400` with the field named rather than a constraint violation.
         */
        @field:Min(1)
        @field:Max(10_000)
        val seats: Int,
    )

    data class WorkspaceResponse(
        val id: UUID,
        val name: String,
        val seats: Int,
        /** Members plus invitations still open: a seat offered is a seat spoken for. */
        val seatsTaken: Int,
        val requireTwoFactor: Boolean,
        val sessionIdleTimeout: String?,
        val myRole: String,
    )

    data class MembershipResponse(val workspaceId: UUID, val role: String, val joinedAt: String)

    data class MemberResponse(val userId: UUID, val role: String, val joinedAt: String)
}
