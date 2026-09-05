package pl.barometr.taxonomy.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * Nothing pending to settle: never recorded, or settled by somebody else first.
 *
 * The second is the reason this is not a 409. Two reviewers reaching the same row is
 * ordinary, the one who arrives second changed nothing, and telling them the row is
 * gone is the truth from where they stand.
 */
class UnknownVerdictException(what: String) : DomainException(ErrorKind.NOT_FOUND, "unknown_verdict") {
    init {
        addSuppressed(IllegalStateException("no pending verdict for $what"))
    }
}
