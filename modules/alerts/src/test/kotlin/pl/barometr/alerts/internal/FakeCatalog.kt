package pl.barometr.alerts.internal

import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeCatalog
import pl.barometr.legislative.api.PublishedAct
import pl.barometr.legislative.api.TrackedDraft
import pl.barometr.shared.Eli
import java.time.LocalDate

/**
 * The archive, as much of it as judging an alert needs: a title, an address, and where
 * a draft currently stands.
 */
class FakeCatalog : LegislativeCatalog {
    private val acts = mutableMapOf<ActId, PublishedAct>()
    private val drafts = mutableMapOf<DraftId, TrackedDraft>()

    fun publish(id: ActId, title: String, eli: Eli) {
        acts[id] = PublishedAct(id, eli, title, "Ustawa", "DU", LocalDate.of(2026, 8, 1), null)
    }

    /** Moving a draft is the same call: the catalog holds where it stands now. */
    fun track(id: DraftId, title: String, stage: String) {
        drafts[id] = TrackedDraft(id, title, "rzadowy", 10, null, null, null, stage, emptyList())
    }

    override fun actById(id: ActId) = acts[id]

    override fun actByEli(eli: Eli) = acts.values.firstOrNull { it.eli == eli }

    override fun draftById(id: DraftId) = drafts[id]

    override fun actsAfter(after: ActId?, limit: Int) = acts.values.toList()

    override fun draftsAfter(after: DraftId?, limit: Int) = drafts.values.toList()
}
