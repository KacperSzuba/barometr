package pl.barometr.alerts.internal

import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeCatalog
import pl.barometr.legislative.api.LegislativeSignals
import pl.barometr.legislative.api.PublishedAct
import pl.barometr.legislative.api.TrackedDraft
import pl.barometr.shared.Eli
import java.time.Instant
import java.time.LocalDate

/**
 * The archive, as much of it as judging an alert needs: a title, an address, where a
 * draft currently stands, and how far along that is.
 *
 * The position is stated rather than derived from the stage. Which stage sits where on
 * the path is legislative's knowledge and is pinned by legislative's own tests; a fake
 * that worked it out again would be a second copy of the answer, and one that could
 * drift.
 */
class FakeCatalog : LegislativeCatalog {
    private val acts = mutableMapOf<ActId, PublishedAct>()
    private val drafts = mutableMapOf<DraftId, TrackedDraft>()
    private val signals = mutableMapOf<DraftId, LegislativeSignals>()

    fun publish(id: ActId, title: String, eli: Eli, inForceFrom: LocalDate? = null) {
        acts[id] = PublishedAct(id, eli, title, "Ustawa", "DU", LocalDate.of(2026, 8, 1), inForceFrom)
    }

    /** Moving a draft is the same call: the catalog holds where it stands now. */
    fun track(
        id: DraftId,
        title: String,
        stage: String,
        progress: Double = 0.0,
        hardDeadlineOn: Instant? = null,
    ) {
        drafts[id] = TrackedDraft(id, title, "rzadowy", 10, null, null, null, stage, emptyList())
        signals[id] = LegislativeSignals(progress, hardDeadlineOn)
    }

    override fun actById(id: ActId) = acts[id]

    override fun actByEli(eli: Eli) = acts.values.firstOrNull { it.eli == eli }

    override fun draftById(id: DraftId) = drafts[id]

    override fun signalsForDraft(id: DraftId) = signals[id]

    override fun actsAfter(after: ActId?, limit: Int) = acts.values.toList()

    override fun draftsAfter(after: DraftId?, limit: Int) = drafts.values.toList()
}
