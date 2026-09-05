package pl.barometr.corpus.internal.diff

import pl.barometr.corpus.api.WordChange

/**
 * What changed inside one unit, and whether it was listed run by run or given up on.
 *
 * [truncated] is said out loud rather than left as a single suspiciously large change:
 * a unit that was rewritten from end to end has no highlights worth drawing, and a
 * reader who is told that is better served than one shown four hundred of them.
 */
data class WordChanges(val changes: List<WordChange>, val truncated: Boolean)
