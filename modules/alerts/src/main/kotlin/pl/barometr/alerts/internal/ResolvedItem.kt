package pl.barometr.alerts.internal

import pl.barometr.legislative.api.LegislativeSignals

/**
 * A buffered item, read back from the catalog at the moment it is judged.
 *
 * [stage] is what a rule narrows on and is null for an act, which has arrived rather
 * than being anywhere. [signals] is what ranking needs on top of that, and is null for
 * a draft nothing is recorded about — which is a thing that can be told about, just
 * not one that can be placed.
 */
data class ResolvedItem(
    val kind: String,
    val id: String,
    val title: String,
    val eli: String?,
    val stage: String?,
    val signals: LegislativeSignals?,
)
