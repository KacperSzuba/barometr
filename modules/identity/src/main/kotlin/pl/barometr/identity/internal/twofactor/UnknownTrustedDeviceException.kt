package pl.barometr.identity.internal.twofactor

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * No such remembered device on this account: never trusted, already forgotten, expired,
 * or somebody else's.
 *
 * One code for all of them, as everywhere else here — a caller can do nothing different
 * with any of them, and confirming which identifiers exist is an answer nobody is owed.
 */
class UnknownTrustedDeviceException(id: String) : DomainException(ErrorKind.NOT_FOUND, "unknown_trusted_device") {
    init {
        addSuppressed(IllegalStateException("no trusted device '$id' for this account"))
    }
}
