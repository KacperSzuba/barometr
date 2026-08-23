package pl.barometr.alerts.internal

import org.springframework.stereotype.Component
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeCatalog
import pl.barometr.legislative.api.LegislativeKind
import pl.barometr.legislative.api.LegislativeSignals
import java.util.UUID

/**
 * Reads a buffered identity back into the thing it names.
 *
 * At judgement time rather than at arrival, so a draft that moved twice while the
 * buffer waited is judged on where it stands now — and so nothing here holds a second,
 * ageing copy of what legislative already knows. Which is also why the ranking signals
 * are read here: a draft's position on the path is exactly the sort of fact that would
 * be stale by the time the window closed.
 */
@Component
class BufferedItemReader(private val catalog: LegislativeCatalog) {

    /** Null when the thing is gone or was never derived — an item nobody can be told about. */
    fun read(item: PendingItem): ResolvedItem? {
        val id = runCatching { UUID.fromString(item.subjectId) }.getOrNull() ?: return null

        return when (item.kind) {
            LegislativeKind.ACT -> catalog.actById(ActId(id))?.let {
                ResolvedItem(
                    kind = LegislativeKind.ACT,
                    id = item.subjectId,
                    title = it.title,
                    eli = it.eli.value,
                    stage = null,
                    // Derived from the act in hand rather than queried again: an act
                    // is at the end of the path by definition, and the only other
                    // thing ranking wants is a date this row already carries.
                    signals = LegislativeSignals.of(it),
                )
            }

            else -> catalog.draftById(DraftId(id))?.let {
                ResolvedItem(
                    kind = LegislativeKind.DRAFT,
                    id = item.subjectId,
                    title = it.title,
                    eli = null,
                    stage = it.currentStage,
                    signals = catalog.signalsForDraft(DraftId(id)),
                )
            }
        }
    }
}
