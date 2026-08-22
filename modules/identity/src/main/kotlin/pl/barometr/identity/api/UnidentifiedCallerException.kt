package pl.barometr.identity.api

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * A verified token whose subject is not one of our user identifiers.
 *
 * It verified, so it was signed with our key — which makes this a credential problem
 * rather than a bad request, and not something a caller can produce by typing.
 */
class UnidentifiedCallerException(subject: String) :
    DomainException(ErrorKind.UNAUTHENTICATED, "invalid_token") {
    init {
        addSuppressed(IllegalStateException("subject '$subject' is not a user id"))
    }
}
