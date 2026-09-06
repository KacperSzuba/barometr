package pl.barometr.identity.internal.auth

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * Too many sign-in attempts, from this address or against this account.
 *
 * Distinct from [InvalidCredentialsException] on purpose, and it is worth saying why
 * given that the two live next to each other: telling somebody the credentials were
 * wrong when the system never looked at them would send a client into a retry loop it
 * cannot escape, and would hide a password-guessing run behind the same answer as a
 * typo. It admits nothing an attacker does not already know — that they have been
 * refused — because the counter is reached identically whether or not the account
 * exists.
 */
class TooManyAttemptsException :
    DomainException(ErrorKind.RATE_LIMITED, "too_many_attempts")
