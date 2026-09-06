package pl.barometr.taxonomy.internal

import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import pl.barometr.legislative.api.ActRecorded
import pl.barometr.legislative.api.DraftRecorded

/**
 * Classifies each act and draft as legislative records it.
 *
 * The edge of the archive, which is where all but the first day's work arrives. The
 * walk over what was already stored exists for that first day — see
 * [UnclassifiedBacklogSweep] — and the two meet in [LegislationTagging], so a subject
 * cannot be classified twice by arriving from both directions.
 */
@Component
class LegislativeClassificationListener(private val tagging: LegislationTagging) {

    @ApplicationModuleListener
    fun classifyRecordedAct(recorded: ActRecorded) {
        tagging.tagAct(recorded.actId)
    }

    @ApplicationModuleListener
    fun classifyRecordedDraft(recorded: DraftRecorded) {
        tagging.tagDraft(recorded.draftId)
    }
}
