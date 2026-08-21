package pl.barometr.search.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeCatalog

/**
 * Rebuilds the whole index from Postgres, into a new index, and switches the alias
 * when it is done.
 *
 * This is the command that makes a second datastore acceptable at all. The index holds
 * nothing that is not derived, so it can be thrown away and rebuilt — after a mapping
 * change, after an outage, after a bug in what was written — and until it is switched
 * to, searches keep being answered by the index already there.
 *
 * Walked by keyset rather than by offset: acts arrive while the walk is running, and an
 * offset over a growing table skips rows.
 */
@Service
class SearchIndexRebuild(
    private val catalog: LegislativeCatalog,
    private val entries: LegislativeEntries,
    private val writer: LegislativeIndexWriter,
    private val maintenance: LegislativeIndexMaintenance,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun rebuild(): RebuildReport {
        val index = maintenance.createIndex()
        log.info("Rebuilding search into {}", index)

        val acts = indexActs(index)
        val drafts = indexDrafts(index)

        maintenance.pointAliasAt(index)
        log.info("Search alias now serves {}: {} acts, {} drafts", index, acts, drafts)

        return RebuildReport(index, acts, drafts)
    }

    private fun indexActs(index: String): Int {
        var after: ActId? = null
        var written = 0

        while (true) {
            val page = catalog.actsAfter(after, PAGE)
            if (page.isEmpty()) return written

            writer.writeAll(index, page.map(entries::entryOf))
            written += page.size
            after = page.last().id
        }
    }

    private fun indexDrafts(index: String): Int {
        var after: DraftId? = null
        var written = 0

        while (true) {
            val page = catalog.draftsAfter(after, PAGE)
            if (page.isEmpty()) return written

            writer.writeAll(index, page.map(entries::entryOf))
            written += page.size
            after = page.last().id
        }
    }

    data class RebuildReport(val index: String, val acts: Int, val drafts: Int)

    private companion object {
        /** Rows per read and per bulk write. Large enough to amortise the round trip. */
        const val PAGE = 500
    }
}
