package pl.barometr.alerts.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/** This profile already has a standing instruction; there is only ever one. */
class DuplicateAlertRuleException(profile: String) :
    DomainException(ErrorKind.CONFLICT, "duplicate_alert_rule") {
    init {
        addSuppressed(IllegalStateException("profile '$profile' already has a rule"))
    }
}
