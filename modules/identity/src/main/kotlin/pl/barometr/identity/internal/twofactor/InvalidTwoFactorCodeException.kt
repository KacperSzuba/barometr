package pl.barometr.identity.internal.twofactor

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * The code was wrong, expired, already used, or not a code at all — all reported the same way, because telling them apart tells an attacker which half they got right.
 */
class InvalidTwoFactorCodeException : DomainException(ErrorKind.UNAUTHENTICATED, "invalid_two_factor_code")
