package pl.barometr.corpus.internal.diff

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * No comparison to show: the document is not in the archive, has only one version, or
 * the versions asked for were never compared.
 *
 * One code for all of them, deliberately — the same reason the consultation calendar
 * gives. A caller can do nothing different with "no such document" than with "nothing
 * compared yet", and distinguishing them would tell an unauthenticated crawler which
 * identifiers exist.
 */
class UnknownComparisonException(what: String) : DomainException(ErrorKind.NOT_FOUND, "unknown_comparison") {
    init {
        addSuppressed(IllegalStateException("no recorded comparison for $what"))
    }
}
