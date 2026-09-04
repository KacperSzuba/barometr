package pl.barometr.legislative.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/** A window that runs backwards, or further than the calendar answers for. */
class InvalidConsultationWindowException(detail: String) : DomainException(ErrorKind.INVALID, "invalid_window") {
    init {
        addSuppressed(IllegalArgumentException(detail))
    }
}
