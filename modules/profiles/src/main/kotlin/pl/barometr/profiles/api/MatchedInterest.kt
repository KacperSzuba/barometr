package pl.barometr.profiles.api

import pl.barometr.profiles.api.InterestKind
/**
 * The thing somebody chose that caught this item — the answer to "why am I being told
 * about this".
 *
 * Carried out of the context rather than left behind, because a notification that
 * cannot say what it was matched on is a notification nobody can act on or turn off.
 */
data class MatchedInterest(val kind: InterestKind, val value: String)
