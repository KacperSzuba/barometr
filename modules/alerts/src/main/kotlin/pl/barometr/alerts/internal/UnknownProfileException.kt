package pl.barometr.alerts.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * A rule can only be made about a profile the caller owns.
 *
 * Reported as absent rather than forbidden, and with the same code profiles itself
 * uses, so that pointing a rule at somebody else's profile tells the caller exactly as
 * much as pointing it at one that never existed.
 */
class UnknownProfileException(id: String) : DomainException(ErrorKind.NOT_FOUND, "unknown_profile") {
    init {
        addSuppressed(IllegalStateException("no profile '$id' for this owner"))
    }
}
