package pl.barometr.legislative.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * No consultation with that identifier, or none this system can date yet.
 *
 * One code for both, deliberately. A consultation whose letter has not been read says
 * nothing about when comments are due, and answering it with an entry missing the one
 * field the entry exists for would be worse than saying there is nothing here.
 */
class UnknownConsultationException(id: String) : DomainException(ErrorKind.NOT_FOUND, "unknown_consultation") {
    init {
        addSuppressed(IllegalStateException("no dated consultation '$id'"))
    }
}
