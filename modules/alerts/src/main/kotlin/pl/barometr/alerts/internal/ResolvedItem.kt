package pl.barometr.alerts.internal

import pl.barometr.legislative.api.LegislativeSignals

/**
 * A buffered item, read back from the catalog at the moment it is judged.
 *
 * [stage] is what a rule narrows on and is null for an act, which has arrived rather
 * than being anywhere. [signals] is what ranking needs on top of that, and is null for
 * a draft nothing is recorded about — which is a thing that can be told about, just
 * not one that can be placed.
 *
 * [notice] is set only on the one item that is not news: a consultation about to close.
 * The four fields above still describe the draft, because that is what somebody
 * subscribed to and what the notification names; the notice is what makes the item a
 * deadline, and it is what [AlertKeys] deduplicates on.
 */
data class ResolvedItem(
    val kind: String,
    val id: String,
    val title: String,
    val eli: String?,
    val stage: String?,
    val signals: LegislativeSignals?,
    val notice: ConsultationNotice? = null,
)
