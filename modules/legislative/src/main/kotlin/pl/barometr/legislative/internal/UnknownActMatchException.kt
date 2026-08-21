package pl.barometr.legislative.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * The match a reviewer asked to decide is not waiting for a decision — it never
 * existed, or somebody else decided it first.
 *
 * One exception for both, because from the caller's side they are the same fact:
 * there is nothing here to act on. A `NOT_FOUND`, not a `CONFLICT`, since the queue
 * is what the reviewer is looking at and the row has left it.
 */
class UnknownActMatchException(id: String) :
    DomainException(ErrorKind.NOT_FOUND, "unknown_act_match") {
    init {
        // The id belongs in the log, not in the response: the code is the contract.
        addSuppressed(IllegalStateException("no pending act match '$id'"))
    }
}
