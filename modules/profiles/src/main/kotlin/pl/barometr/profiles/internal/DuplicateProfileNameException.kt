package pl.barometr.profiles.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/** This account already has a profile under that name. */
class DuplicateProfileNameException(name: String) :
    DomainException(ErrorKind.CONFLICT, "duplicate_profile_name") {
    init {
        addSuppressed(IllegalStateException("profile named '$name' already exists"))
    }
}
