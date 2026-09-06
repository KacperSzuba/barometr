package pl.barometr.search.internal

import org.springframework.stereotype.Component
import pl.barometr.corpus.api.ArchivedDocument
import java.time.Clock

/**
 * Turns an archived document and its text into what the index holds.
 *
 * Its own class for the reason [LegislativeEntries] is: a rebuild and a single update
 * must produce byte-identical documents, or a rebuilt index quietly differs from an
 * incrementally maintained one and nobody finds out until a search stops matching.
 */
@Component
class DocumentEntries(private val clock: Clock) {

    fun entryOf(document: ArchivedDocument, text: String) = IndexedEntry(
        id = IndexedEntry.idOf(IndexedEntry.DOCUMENT, document.id),
        kind = IndexedEntry.DOCUMENT,
        // A filed document is often untitled — RPL names it on the catalog page rather
        // than inside the file — and its address is then the only name it has. Better a
        // reader sees `projekt/12409051/…/dokument/9` than an empty line.
        title = document.title ?: document.externalId.value,
        // What the source calls it, so pasting an address out of a URL finds the file.
        identifiers = listOf(document.externalId.value),
        content = withinHighlightingReach(text),
        announcedOn = document.publishedAt?.toString(),
        indexedAt = clock.instant().toString(),
    )

    /**
     * The text, cut to what Elasticsearch will highlight.
     *
     * `index.highlight.max_analyzed_offset` defaults to a million characters, and a
     * query that has to highlight a longer field does not return a shorter snippet — it
     * fails, and takes the whole search with it. So the cut is made here, once, where it
     * can be explained: a million characters is around five hundred pages, longer than
     * any bill filed in RPL and longer than every impact assessment beside them. What is
     * lost is the tail of the handful of documents past that length, and what is bought
     * is that no single enormous file can break search for everybody.
     */
    private fun withinHighlightingReach(text: String): String = text.take(HIGHLIGHTABLE)

    private companion object {
        const val HIGHLIGHTABLE = 1_000_000
    }
}
