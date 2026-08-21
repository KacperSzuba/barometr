package pl.barometr.search.internal

import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.legislative.api.ActRecorded
import pl.barometr.legislative.api.DraftRecorded
import pl.barometr.legislative.api.LegislativeCatalog

/**
 * Keeps the index in step with what legislative records, one event at a time.
 *
 * The event says which act or draft moved and nothing else; what it now looks like is
 * read back through the published port. That is deliberate — an event carrying a copy
 * of the thing would put a second description of every act in the publication register,
 * to go stale the moment the first one changed.
 *
 * Writes go through the alias, so they land in whichever index is live — including one
 * a rebuild has just switched to. A document that changes *during* a rebuild can miss
 * the new index, since the rebuild's own walk read it before the change; the next event
 * about it puts that right, and rebuilds are rare and deliberate.
 *
 * Failure here is not failure of anything upstream. The publication register redelivers
 * a listener that threw, so an index that was briefly unreachable catches up on its own.
 */
@Service
class LegislativeIndexer(
    private val catalog: LegislativeCatalog,
    private val entries: LegislativeEntries,
    private val writer: LegislativeIndexWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun indexAct(recorded: ActRecorded) {
        val act = catalog.actById(recorded.actId)
        if (act == null) {
            log.debug("Act {} is not indexable yet", recorded.actId)
            return
        }

        writer.write(LegislativeIndex.ALIAS, entries.entryOf(act))
    }

    @ApplicationModuleListener
    fun indexDraft(recorded: DraftRecorded) {
        val draft = catalog.draftById(recorded.draftId) ?: return

        writer.write(LegislativeIndex.ALIAS, entries.entryOf(draft))
    }
}
