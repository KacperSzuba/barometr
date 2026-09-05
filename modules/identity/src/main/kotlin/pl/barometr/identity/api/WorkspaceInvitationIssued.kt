package pl.barometr.identity.api

import java.time.Instant

/**
 * Published when a seat has been offered to an address.
 *
 * Identity issues the invitation and takes no view on how it reaches anybody: sending
 * mail is delivery, suppression and reputation, which belong to the context that owns
 * all three. Without this event an invitation is a row and a link nobody was ever sent.
 *
 * It carries the link rather than the token, because whoever sends it should not have to
 * know how one is turned into the other — and it carries the workspace's name because a
 * message saying "you have been invited" without saying to what is a message people
 * report as spam.
 */
data class WorkspaceInvitationIssued(
    val email: String,
    val workspaceName: String,
    val invitedBy: String,
    val acceptUrl: String,
    val expiresAt: Instant,
    val occurredAt: Instant,
)
