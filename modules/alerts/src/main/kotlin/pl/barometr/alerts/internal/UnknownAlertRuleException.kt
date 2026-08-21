package pl.barometr.alerts.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * No such rule — or one belonging to somebody else, answered the same way on purpose.
 */
class UnknownAlertRuleException(id: String) : DomainException(ErrorKind.NOT_FOUND, "unknown_alert_rule") {
    init {
        addSuppressed(IllegalStateException("no rule '$id' for this owner"))
    }
}
