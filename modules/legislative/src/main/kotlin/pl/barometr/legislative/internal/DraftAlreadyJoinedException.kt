package pl.barometr.legislative.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * One half of the pair a reviewer accepted is already part of another join.
 *
 * A `CONFLICT` rather than a quiet no-op, because the alternative is the worst outcome
 * available: the queue would show the item decided while nothing about the two drafts
 * had changed, and the reviewer would have no way to tell. The existing join has to be
 * undone before this one can be made.
 */
class DraftAlreadyJoinedException(id: String) :
    DomainException(ErrorKind.CONFLICT, "draft_already_joined") {
    init {
        // The id belongs in the log, not in the response: the code is the contract.
        addSuppressed(IllegalStateException("draft '$id' is already joined across the registers"))
    }
}
