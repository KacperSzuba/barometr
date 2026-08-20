package pl.barometr.identity.internal.auth

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

class InvalidRefreshTokenException :
    DomainException(ErrorKind.UNAUTHENTICATED, "invalid_refresh_token")
