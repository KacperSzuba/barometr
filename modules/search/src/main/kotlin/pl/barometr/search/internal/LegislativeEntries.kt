package pl.barometr.search.internal

import org.springframework.stereotype.Component
import pl.barometr.legislative.api.PublishedAct
import pl.barometr.legislative.api.TrackedDraft
import java.time.Clock

/**
 * Turns what legislative publishes into what the index holds.
 *
 * Its own class because it is the only place the two shapes meet, and because a
 * rebuild and a single update have to produce byte-identical documents — otherwise a
 * rebuilt index quietly differs from an incrementally maintained one and nobody finds
 * out until a search stops matching.
 */
@Component
class LegislativeEntries(private val clock: Clock) {

    fun entryOf(act: PublishedAct) = IndexedEntry(
        id = IndexedEntry.idOf(IndexedEntry.ACT, act.id),
        kind = IndexedEntry.ACT,
        title = act.title,
        eli = act.eli.value,
        actType = act.type,
        publisher = act.publisher,
        // An act carries its own address, and people quote acts by it.
        identifiers = listOf(act.eli.value),
        announcedOn = act.announcedOn?.toString(),
        inForceFrom = act.inForceFrom?.toString(),
        indexedAt = clock.instant().toString(),
    )

    fun entryOf(draft: TrackedDraft) = IndexedEntry(
        id = IndexedEntry.idOf(IndexedEntry.DRAFT, draft.id),
        kind = IndexedEntry.DRAFT,
        title = draft.title,
        initiator = draft.initiator,
        term = draft.term,
        stage = draft.currentStage,
        outcome = draft.outcome,
        identifiers = draft.identifiers,
        startedOn = draft.startedOn?.toString(),
        indexedAt = clock.instant().toString(),
    )
}
