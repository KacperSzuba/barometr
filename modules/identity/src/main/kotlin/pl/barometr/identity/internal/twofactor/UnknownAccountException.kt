package pl.barometr.identity.internal.twofactor

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * A token that names an account this system no longer holds.
 *
 * Reachable in one narrow case — a signed token outliving the account it was minted for
 * — and reported rather than left to become a null pointer inside enrolment.
 */
class UnknownAccountException : DomainException(ErrorKind.NOT_FOUND, "unknown_account")
