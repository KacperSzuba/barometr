package pl.barometr.taxonomy.internal

import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeCatalog
import pl.barometr.legislative.api.LegislativeSignals
import pl.barometr.legislative.api.PublishedAct
import pl.barometr.legislative.api.TrackedDraft
import pl.barometr.shared.Eli
import pl.barometr.shared.Ids

/**
 * Acts and drafts, as much of them as a classifier needs: a title and an identifier.
 *
 * Insertion order stands in for the identifier order the real catalogue pages by — both
 * are the order the rows were written in, which is what makes a keyset walk finish.
 */
class FakeLegislation : LegislativeCatalog {
    private val acts = linkedMapOf<ActId, PublishedAct>()
    private val drafts = linkedMapOf<DraftId, TrackedDraft>()

    fun publish(title: String): ActId {
        val id = ActId(Ids.next())
        acts[id] = PublishedAct(
            id = id,
            eli = Eli("DU/2024/${acts.size + 1}"),
            title = title,
            type = "Ustawa",
            publisher = "DU",
            announcedOn = null,
            inForceFrom = null,
        )

        return id
    }

    fun table(title: String): DraftId {
        val id = DraftId(Ids.next())
        drafts[id] = TrackedDraft(
            id = id,
            title = title,
            initiator = "rzadowy",
            term = 10,
            startedOn = null,
            closedOn = null,
            outcome = null,
            currentStage = null,
            identifiers = emptyList(),
        )

        return id
    }

    override fun actById(id: ActId): PublishedAct? = acts[id]

    override fun actByEli(eli: Eli): PublishedAct? = acts.values.firstOrNull { it.eli == eli }

    override fun draftById(id: DraftId): TrackedDraft? = drafts[id]

    override fun signalsForDraft(id: DraftId): LegislativeSignals? = null

    override fun actsAfter(after: ActId?, limit: Int): List<PublishedAct> = page(acts.values.toList(), after, limit)

    override fun draftsAfter(after: DraftId?, limit: Int): List<TrackedDraft> =
        page(drafts.values.toList(), after, limit)

    private fun <T : Any> page(all: List<T>, after: Any?, limit: Int): List<T> {
        val remaining = if (after == null) all else all.dropWhile { idOf(it) != after }.drop(1)

        return remaining.take(limit)
    }

    private fun idOf(subject: Any): Any = when (subject) {
        is PublishedAct -> subject.id
        is TrackedDraft -> subject.id
        else -> error("neither an act nor a draft")
    }
}
