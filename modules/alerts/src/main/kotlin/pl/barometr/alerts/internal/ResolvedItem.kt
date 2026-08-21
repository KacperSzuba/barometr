package pl.barometr.alerts.internal

/**
 * A buffered item, read back from the catalog at the moment it is judged.
 *
 * [stage] is what a rule narrows on and is null for an act, which has arrived rather
 * than being anywhere.
 */
data class ResolvedItem(
    val kind: String,
    val id: String,
    val title: String,
    val eli: String?,
    val stage: String?,
)
