package pl.barometr.identity.internal.auth

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * Deliberately identical for an unknown e-mail and a wrong password — telling
 * the two apart would let anyone enumerate registered accounts.
 */
class InvalidCredentialsException :
    DomainException(ErrorKind.UNAUTHENTICATED, "invalid_credentials")

class InvalidRefreshTokenException :
    DomainException(ErrorKind.UNAUTHENTICATED, "invalid_refresh_token")

/** A refresh token was presented twice outside the race window: assume theft. */
class RefreshTokenReuseException :
    DomainException(ErrorKind.UNAUTHENTICATED, "refresh_token_reuse")

class EmailAlreadyUsedException :
    DomainException(ErrorKind.CONFLICT, "email_already_used")
