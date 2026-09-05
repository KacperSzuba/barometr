package pl.barometr.identity.internal.workspace

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.barometr.identity.api.callerOf
import java.security.Principal
import java.util.UUID

/**
 * Offering a seat, and taking one.
 *
 * Accepting is a signed-in route, and that is the design rather than a limitation:
 * an invitation is offered to an address, so taking it means proving you are the account
 * that holds that address — which is what signing in is. Somebody who has not registered
 * registers first, with the same address, and the link still works.
 *
 * The token never appears in a response. It goes out in one message, to one address, and
 * lives here only as a hash.
 */
@RestController
@RequestMapping("/api/v1/workspaces")
class WorkspaceInvitationController(private val invitations: TeamInvitations) {

    @PostMapping("/{id}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    fun invite(
        caller: Principal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: InviteRequest,
    ): InvitationResponse {
        val role = WorkspaceRole.of(request.role) ?: throw UnknownWorkspaceRoleException(request.role)

        return describe(invitations.invite(callerOf(caller), WorkspaceId(id), request.email, role))
    }

    @GetMapping("/{id}/invitations")
    fun open(caller: Principal, @PathVariable id: UUID): List<InvitationResponse> =
        invitations.openInvitations(callerOf(caller), WorkspaceId(id)).map(::describe)

    @DeleteMapping("/{id}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(caller: Principal, @PathVariable id: UUID, @PathVariable invitationId: UUID) {
        invitations.revokeInvitation(callerOf(caller), WorkspaceId(id), invitationId)
    }

    @PostMapping("/invitations/{token}/acceptance")
    fun accept(caller: Principal, @PathVariable token: String): AcceptedResponse {
        val membership = invitations.acceptInvitation(callerOf(caller), token)

        return AcceptedResponse(membership.workspace.value, membership.role.wireName)
    }

    private fun describe(invitation: PendingInvitation) = InvitationResponse(
        id = invitation.id,
        email = invitation.email,
        role = invitation.role.wireName,
        expiresAt = invitation.expiresAt.toString(),
    )

    data class InviteRequest(
        @field:Email
        @field:NotBlank
        val email: String,
        @field:NotBlank
        val role: String,
    )

    /** Everything about an invitation except the one thing that would let anybody use it. */
    data class InvitationResponse(val id: UUID, val email: String, val role: String, val expiresAt: String)

    data class AcceptedResponse(val workspaceId: UUID, val role: String)
}
