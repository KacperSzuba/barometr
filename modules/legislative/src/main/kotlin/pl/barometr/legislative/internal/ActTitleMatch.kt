package pl.barometr.legislative.internal

import pl.barometr.legislative.api.ActId
import pl.barometr.shared.Eli

/** The closest act by title, and how close it actually was. */
data class ActTitleMatch(
    val actId: ActId,
    val eli: Eli,
    /** pg_trgm similarity, between 0 and 1. */
    val similarity: Double,
)
