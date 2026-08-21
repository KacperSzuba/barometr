package pl.barometr.search.internal

/** One result, with the part of the title that matched marked up. */
data class SearchHit(
    val id: String,
    val kind: String,
    val title: String,
    /** The title with matches marked, or null when the hit was not on the title. */
    val highlightedTitle: String?,
    val eli: String?,
    val stage: String?,
    val outcome: String?,
    val score: Double,
)
