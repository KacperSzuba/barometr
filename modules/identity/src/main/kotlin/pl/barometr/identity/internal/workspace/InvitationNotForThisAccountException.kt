package pl.barometr.identity.internal.workspace

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * The link is real and was sent to somebody else's address.
 *
 * Refused rather than honoured: an invitation names an address because a seat is offered
 * to a person, and a link forwarded to a colleague must not quietly make them a member of
 * a workspace their employer is paying for.
 */
class InvitationNotForThisAccountException :
    DomainException(ErrorKind.FORBIDDEN, "invitation_not_for_this_account")
