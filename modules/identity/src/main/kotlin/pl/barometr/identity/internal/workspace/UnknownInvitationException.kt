package pl.barometr.identity.internal.workspace

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * No open invitation: never issued, already taken, revoked, or expired. All four look the same to whoever is holding the link.
 */
class UnknownInvitationException : DomainException(ErrorKind.NOT_FOUND, "unknown_invitation")
