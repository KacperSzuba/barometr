package pl.barometr.identity.internal.apikey

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * No such live key on this account: never issued, already revoked, or somebody else's.
 *
 * One code for all three, as everywhere else here — none of them is anything the caller
 * can act on differently, and confirming which identifiers exist is an answer nobody is
 * owed.
 */
class UnknownApiKeyException(id: String) : DomainException(ErrorKind.NOT_FOUND, "unknown_api_key") {
    init {
        addSuppressed(IllegalStateException("no live API key '$id' for this account"))
    }
}
