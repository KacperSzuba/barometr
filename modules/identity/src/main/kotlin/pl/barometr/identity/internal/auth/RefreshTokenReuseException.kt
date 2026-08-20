package pl.barometr.identity.internal.auth

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/** A refresh token was presented twice outside the race window: assume theft. */
class RefreshTokenReuseException :
    DomainException(ErrorKind.UNAUTHENTICATED, "refresh_token_reuse")
