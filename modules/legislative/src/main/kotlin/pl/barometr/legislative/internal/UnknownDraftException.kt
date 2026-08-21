package pl.barometr.legislative.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/** No draft with that identifier — a caller's mistake, not the server's. */
class UnknownDraftException(id: String) : DomainException(ErrorKind.NOT_FOUND, "unknown_draft") {
    init {
        // The id belongs in the log, not in the response: the code is the contract.
        addSuppressed(IllegalStateException("no draft '$id'"))
    }
}
