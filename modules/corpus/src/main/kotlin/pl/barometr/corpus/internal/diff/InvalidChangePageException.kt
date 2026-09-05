package pl.barometr.corpus.internal.diff

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * A page of changes that was asked for in a size nobody can render.
 *
 * Refused rather than quietly clamped: a caller asking for five thousand changes has
 * misunderstood the endpoint, and answering with two hundred and no explanation is how
 * a client ends up paging in a loop that never ends.
 */
class InvalidChangePageException(what: String) : DomainException(ErrorKind.INVALID, "invalid_change_page") {
    init {
        addSuppressed(IllegalArgumentException(what))
    }
}
