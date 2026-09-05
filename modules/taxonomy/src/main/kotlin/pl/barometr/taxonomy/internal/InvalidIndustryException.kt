package pl.barometr.taxonomy.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * Something that is not an industry code, or not a subject that can carry one.
 *
 * A caller's mistake and reported as one: a section letter, a typo, or `sejm` where a
 * kind belongs. Keeping these as `error(...)` would answer a typo with a server fault,
 * which is the failure `DomainException` exists to prevent.
 */
class InvalidIndustryException(what: String) : DomainException(ErrorKind.INVALID, "invalid_industry") {
    init {
        addSuppressed(IllegalArgumentException(what))
    }
}
