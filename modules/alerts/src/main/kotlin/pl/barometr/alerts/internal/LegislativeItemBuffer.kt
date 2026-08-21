package pl.barometr.alerts.internal

import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.legislative.api.ActRecorded
import pl.barometr.legislative.api.DraftRecorded
import pl.barometr.legislative.api.LegislativeKind

/**
 * Writes down that something moved, and decides nothing.
 *
 * Matching is a batch, per the same reasoning that keeps the index rebuild off the
 * event path: one crawl of the Journal of Laws restates thousands of acts, and asking
 * every profile about each of them as it arrives turns one cheap question into
 * thousands of expensive ones. So the arrival is cheap and the judgement is periodic.
 *
 * In the database rather than a queue in memory, because a restart between "an act was
 * recorded" and "somebody was told about it" must lose neither — that gap is exactly
 * where a missed alert would be invisible.
 */
@Service
class LegislativeItemBuffer(private val pending: PendingItemRepository) {

    @ApplicationModuleListener
    fun bufferAct(recorded: ActRecorded) {
        pending.append(LegislativeKind.ACT, recorded.actId.value.toString())
    }

    @ApplicationModuleListener
    fun bufferDraft(recorded: DraftRecorded) {
        pending.append(LegislativeKind.DRAFT, recorded.draftId.value.toString())
    }
}
