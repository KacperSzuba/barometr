package pl.barometr.alerts.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * A cadence this system cannot keep: an unknown mode, a zone that is not a zone, a
 * weekly digest with no day, quiet hours that start and end at the same time.
 *
 * Refused rather than stored, because every one of those is a setting that would look
 * chosen and behave like silence.
 */
class InvalidCadenceException(value: String) : DomainException(ErrorKind.INVALID, "invalid_cadence") {
    init {
        addSuppressed(IllegalStateException("cannot deliver on '$value'"))
    }
}
