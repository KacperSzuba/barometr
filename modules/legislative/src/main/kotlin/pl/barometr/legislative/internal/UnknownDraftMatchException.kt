package pl.barometr.legislative.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * The join a reviewer asked to decide is not waiting for a decision — it never
 * existed, or somebody else decided it first. A `NOT_FOUND` for the same reason
 * [UnknownActMatchException] is: the queue is what the reviewer is looking at, and the
 * row has left it.
 */
class UnknownDraftMatchException(id: String) :
    DomainException(ErrorKind.NOT_FOUND, "unknown_draft_match") {
    init {
        // The id belongs in the log, not in the response: the code is the contract.
        addSuppressed(IllegalStateException("no pending draft match '$id'"))
    }
}
