package pl.barometr.identity.internal.workspace

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.UserLookup
import pl.barometr.identity.api.WorkspaceInvitationIssued
import pl.barometr.shared.Ids
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

/**
 * Offering somebody a seat, and taking one.
 *
 * **An invitation names an address, not an account.** Most of them go to people who have
 * not registered yet, and the flow exists so that they can: the link survives
 * registration, and accepting it requires being signed in as the address it was sent to.
 * A link forwarded to a colleague does not quietly make them a member of a workspace
 * somebody else is paying for.
 *
 * **Only the hash of the token is stored**, for the reason every other capability here
 * gives: a database dump must not be a set of usable invitations.
 */
@Service
class TeamInvitations(
    private val invitations: WorkspaceInvitations,
    private val workspaces: Workspaces,
    private val team: TeamWorkspaces,
    private val users: UserLookup,
    private val properties: WorkspaceProperties,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    /**
     * Offers a seat, and announces it so that something can send the link.
     *
     * The seat is counted the moment the invitation goes out: a workspace with five seats
     * and five open invitations is full, or the sixth person to accept would be a member
     * nobody sold a seat to.
     */
    @Transactional
    fun invite(caller: UserId, workspace: WorkspaceId, email: String, role: WorkspaceRole): PendingInvitation {
        team.administrator(caller, workspace)

        val address = email.trim().lowercase()
        val organisation = workspaces.byId(workspace) ?: throw UnknownWorkspaceException()
        if (team.taken(workspace) >= organisation.seats) throw NoSeatsLeftException()

        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(TOKEN_BYTES).also(random::nextBytes),
        )
        val now = clock.instant()

        val invitation = invitations.issue(
            PendingInvitation(
                id = Ids.next(),
                workspace = workspace,
                email = address,
                role = role,
                tokenHash = hash(token),
                invitedBy = caller,
                createdAt = now,
                expiresAt = now.plus(properties.invitationTtl),
                acceptedAt = null,
                revokedAt = null,
            ),
        )

        events.publishEvent(
            WorkspaceInvitationIssued(
                email = address,
                workspaceName = organisation.name,
                invitedBy = users.findById(caller)?.email.orEmpty(),
                acceptUrl = "${properties.invitationBaseUrl}$ACCEPT_PATH/$token",
                expiresAt = invitation.expiresAt,
                occurredAt = now,
            ),
        )
        log.info("Seat in workspace {} offered to {} by {}", workspace, address, caller.value)

        return invitation
    }

    @Transactional(readOnly = true)
    fun openInvitations(caller: UserId, workspace: WorkspaceId): List<PendingInvitation> {
        team.administrator(caller, workspace)

        return invitations.openIn(workspace)
    }

    @Transactional
    fun revokeInvitation(caller: UserId, workspace: WorkspaceId, id: UUID) {
        team.administrator(caller, workspace)

        if (!invitations.revoke(workspace, id, clock.instant())) throw UnknownInvitationException()
    }

    /**
     * Takes a seat, for the account whose address it was offered to.
     *
     * The seat is counted again here rather than trusted from when the invitation went
     * out: seats can be given back between the two, and the alternative is a workspace
     * that quietly exceeds what it pays for.
     */
    @Transactional
    fun acceptInvitation(caller: UserId, token: String): WorkspaceMembership {
        val now = clock.instant()
        val invitation = invitations.byTokenHash(hash(token), now) ?: throw UnknownInvitationException()

        val address = users.findById(caller)?.email?.lowercase() ?: throw UnknownInvitationException()
        if (address != invitation.email) throw InvitationNotForThisAccountException()

        val workspace = workspaces.byId(invitation.workspace) ?: throw UnknownWorkspaceException()
        if (workspaces.countMembers(invitation.workspace) >= workspace.seats) throw NoSeatsLeftException()

        // The claim, not a check: two people opening the same link means one takes the
        // seat and the other is told there is nothing to take.
        if (!invitations.accept(invitation.id, now)) throw UnknownInvitationException()

        val membership = WorkspaceMembership(invitation.workspace, caller, invitation.role, now)
        workspaces.addMember(membership)
        log.info("{} joined workspace {} as {}", caller.value, invitation.workspace, invitation.role.wireName)

        return membership
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val TOKEN_BYTES = 32

        /** Where the link lands. The web application routes it; the API accepts the token. */
        const val ACCEPT_PATH = "/zaproszenia"
    }
}
