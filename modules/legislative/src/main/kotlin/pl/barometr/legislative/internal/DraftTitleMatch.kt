package pl.barometr.legislative.internal

import pl.barometr.legislative.api.DraftId

/** The closest draft in the other register by title, and how close it actually was. */
data class DraftTitleMatch(
    val draftId: DraftId,
    val title: String,
    /** pg_trgm similarity, between 0 and 1. */
    val similarity: Double,
)
