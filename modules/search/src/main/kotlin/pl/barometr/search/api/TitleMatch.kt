package pl.barometr.search.api

/**
 * One thing a phrase found, named the way its own context names it — `act` or `draft`
 * plus that context's identifier, not the index's.
 */
data class TitleMatch(
    val kind: String,
    val id: String,
    val title: String,
    val eli: String?,
)
