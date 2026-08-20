package pl.barometr.identity.internal.auth

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * Deliberately identical for an unknown e-mail and a wrong password — telling
 * the two apart would let anyone enumerate registered accounts.
 */
class InvalidCredentialsException :
    DomainException(ErrorKind.UNAUTHENTICATED, "invalid_credentials")
