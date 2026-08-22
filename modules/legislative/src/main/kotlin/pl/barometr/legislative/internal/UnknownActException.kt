package pl.barometr.legislative.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/** No act at that identifier or that address — a caller's mistake, not the server's. */
class UnknownActException(id: String) : DomainException(ErrorKind.NOT_FOUND, "unknown_act") {
    init {
        // The address belongs in the log, not in the response: the code is the contract.
        addSuppressed(IllegalStateException("no act '$id'"))
    }
}
