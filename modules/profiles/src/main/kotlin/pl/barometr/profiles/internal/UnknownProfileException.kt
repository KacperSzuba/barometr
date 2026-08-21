package pl.barometr.profiles.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * No such profile — or one belonging to somebody else, which is answered the same way
 * on purpose. A `403` on another account's profile would confirm that it exists, and
 * a profile's name is the sort of thing that is worth guessing.
 */
class UnknownProfileException(id: String) : DomainException(ErrorKind.NOT_FOUND, "unknown_profile") {
    init {
        addSuppressed(IllegalStateException("no profile '$id' for this owner"))
    }
}
