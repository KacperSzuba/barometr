package pl.barometr.alerts.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/** A verified token whose subject is not one of our user identifiers. */
class UnidentifiedCallerException(subject: String) :
    DomainException(ErrorKind.UNAUTHENTICATED, "invalid_token") {
    init {
        addSuppressed(IllegalStateException("subject '$subject' is not a user id"))
    }
}
