package pl.barometr.legislative.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * Something in the shape of an address that is not one.
 *
 * Separate from "no act there", because they are different mistakes: one is a typo in
 * the request, the other a question this archive cannot answer yet. An identifier that
 * is not a UUID already comes back as a bad request on the sibling route, and an
 * address should not be treated more leniently for having slashes in it.
 */
class InvalidActAddressException(address: String) :
    DomainException(ErrorKind.INVALID, "invalid_act_address") {
    init {
        addSuppressed(IllegalStateException("'$address' is not an ELI"))
    }
}
