package pl.barometr.identity.internal.apikey

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * Something that is not one of the scopes a key may have.
 *
 * A caller's mistake reported as one, rather than a key quietly issued with fewer scopes
 * than were asked for — which would fail later, on a route the owner believed they had
 * access to.
 */
class UnknownApiScopeException(scope: String) : DomainException(ErrorKind.INVALID, "unknown_api_scope") {
    init {
        addSuppressed(IllegalArgumentException("no API scope '$scope'"))
    }
}
