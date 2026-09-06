package pl.barometr.search.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.barometr.corpus.api.ArchivedVersion
import pl.barometr.corpus.api.DocumentCatalog
import pl.barometr.corpus.api.DocumentId
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeCatalog
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore

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
    private val documents: DocumentCatalog,
    private val blobs: BlobStore,
    private val entries: LegislativeEntries,
    private val documentEntries: DocumentEntries,
    private val writer: LegislativeIndexWriter,
    private val maintenance: LegislativeIndexMaintenance,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun rebuild(): RebuildReport {
        val index = maintenance.createIndex()
        log.info("Rebuilding search into {}", index)

        val acts = indexActs(index)
        val drafts = indexDrafts(index)
        val documents = indexDocuments(index)

        maintenance.pointAliasAt(index)
        log.info(
            "Search alias now serves {}: {} acts, {} drafts, {} documents",
            index,
            acts,
            drafts,
            documents,
        )

        return RebuildReport(index, acts, drafts, documents)
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

    /**
     * Every document of a searchable kind, read back out of the archive.
     *
     * This is the walk that makes the text index rebuildable, and it is also how a
     * document indexed before its text existed — or missed while the index was down —
     * gets in. Walked per kind and by identity within a kind, for the same reason the
     * two above are: a document stored while the walk is running must not be skipped.
     */
    private fun indexDocuments(index: String): Int {
        var written = 0

        SearchableDocuments.KINDS.forEach { kind ->
            var after: DocumentId? = null

            while (true) {
                val page = documents.versionsOfKind(kind, after, PAGE)
                if (page.isEmpty()) break

                val entries = page.mapNotNull(::entryOf)
                writer.writeAll(index, entries)
                written += entries.size
                after = page.last().documentId
            }
        }

        return written
    }

    /**
     * One document's entry, or null when there is nothing to index yet.
     *
     * The title is fetched per document rather than carried by the walk, which is a
     * query per row and deliberate: the entry a rebuild writes has to be identical to
     * the one the indexer writes from an event, and the title is part of it. A rebuild
     * is a rare, explicit command over an archive of tens of thousands, not a request
     * path.
     */
    private fun entryOf(version: ArchivedVersion): IndexedEntry? {
        val textHash = version.textHash ?: return null
        val document = documents.documentById(version.documentId) ?: return null
        val text = blobs.read(BlobBucket.DERIVED, textHash)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return null

        return documentEntries.entryOf(document, text)
    }

    data class RebuildReport(val index: String, val acts: Int, val drafts: Int, val documents: Int)

    private companion object {
        /** Rows per read and per bulk write. Large enough to amortise the round trip. */
        const val PAGE = 500
    }
}
