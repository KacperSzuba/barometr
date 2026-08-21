package pl.barometr.profiles.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * A chosen value that its kind cannot read.
 *
 * Refused at the edge rather than stored and ignored: an interest nothing can match is
 * indistinguishable, to the person who wrote it, from a quiet fortnight in the Sejm.
 */
class InvalidInterestException(kind: String, value: String) :
    DomainException(ErrorKind.INVALID, "invalid_interest") {
    init {
        addSuppressed(IllegalStateException("'$value' is not a $kind"))
    }
}
