package pl.barometr.identity.internal.auth

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * No such live session on this account: never opened, already ended, or somebody
 * else's.
 *
 * One code for all three. A caller can do nothing different with any of them, and
 * telling an authenticated stranger which session identifiers exist is an answer nobody
 * is owed.
 */
class UnknownSessionException(id: String) : DomainException(ErrorKind.NOT_FOUND, "unknown_session") {
    init {
        addSuppressed(IllegalStateException("no live session '$id' for this account"))
    }
}
