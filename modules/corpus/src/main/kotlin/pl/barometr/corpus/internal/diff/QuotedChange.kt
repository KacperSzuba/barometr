package pl.barometr.corpus.internal.diff

import pl.barometr.corpus.api.UnitChange

/**
 * One change with the words it is about, read back out of the two versions.
 *
 * Quoted here rather than stored beside the change: the archive is content-addressed,
 * so the text a range points into cannot have moved under it, and a stored copy would
 * be a second version of the truth that can only ever be wrong. A quote is null when
 * the side does not exist — nothing was there before an addition — or when the stored
 * text has become unreadable, which is a thing to notice rather than to paper over.
 */
data class QuotedChange(
    val change: UnitChange,
    val before: String?,
    val after: String?,
)
