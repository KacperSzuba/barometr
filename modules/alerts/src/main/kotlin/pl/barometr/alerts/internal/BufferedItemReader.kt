package pl.barometr.alerts.internal

import org.springframework.stereotype.Component
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeCatalog
import pl.barometr.legislative.api.LegislativeKind
import java.util.UUID

/**
 * Reads a buffered identity back into the thing it names.
 *
 * At judgement time rather than at arrival, so a draft that moved twice while the
 * buffer waited is judged on where it stands now — and so nothing here holds a second,
 * ageing copy of what legislative already knows.
 */
@Component
class BufferedItemReader(private val catalog: LegislativeCatalog) {

    /** Null when the thing is gone or was never derived — an item nobody can be told about. */
    fun read(item: PendingItem): ResolvedItem? {
        val id = runCatching { UUID.fromString(item.subjectId) }.getOrNull() ?: return null

        return when (item.kind) {
            LegislativeKind.ACT -> catalog.actById(ActId(id))?.let {
                ResolvedItem(LegislativeKind.ACT, item.subjectId, it.title, it.eli.value, stage = null)
            }

            else -> catalog.draftById(DraftId(id))?.let {
                ResolvedItem(LegislativeKind.DRAFT, item.subjectId, it.title, eli = null, stage = it.currentStage)
            }
        }
    }
}
