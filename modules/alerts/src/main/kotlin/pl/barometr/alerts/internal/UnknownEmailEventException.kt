package pl.barometr.alerts.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * An event this system has no rule for.
 *
 * Refused rather than ignored: a provider adapter reporting `hard_bounce` where this
 * expects `bounced` would otherwise return 204 and suppress nothing, which looks
 * exactly like a mailing list with no bounces at all.
 */
class UnknownEmailEventException(event: String) :
    DomainException(ErrorKind.INVALID, "unknown_email_event") {
    init {
        addSuppressed(IllegalStateException("no rule for '$event'"))
    }
}
