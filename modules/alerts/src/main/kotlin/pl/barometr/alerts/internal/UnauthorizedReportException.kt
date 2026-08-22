package pl.barometr.alerts.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/** A bounce report presenting the wrong secret, or none. */
class UnauthorizedReportException :
    DomainException(ErrorKind.UNAUTHENTICATED, "invalid_webhook_secret")
