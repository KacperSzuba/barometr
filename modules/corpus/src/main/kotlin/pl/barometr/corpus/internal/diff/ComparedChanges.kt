package pl.barometr.corpus.internal.diff

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.corpus.api.UnitChange
import pl.barometr.corpus.api.VersionDiff
import pl.barometr.shared.ContentHash
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore

/**
 * A recorded comparison, read back with the words it is about.
 *
 * The layer between the endpoint and the tables: it decides which comparison answers
 * the question, pages the changes, and quotes both sides out of the archive. The
 * controller then has nothing left to decide, which is the point — the same reading is
 * available to anything else that wants it without going through HTTP.
 *
 * Both texts are read once per page rather than once per change. A page of fifty
 * changes over a three-hundred-page bill would otherwise be a hundred object-store
 * reads of the same two blobs.
 */
@Service
@Transactional(readOnly = true)
class ComparedChanges(
    private val diffs: VersionDiffRepository,
    private val blobs: BlobStore,
) {

    fun changesOf(
        documentId: DocumentId,
        from: DocumentVersionId?,
        to: DocumentVersionId?,
        substantiveOnly: Boolean,
        afterOrdinal: Int,
        limit: Int,
    ): QuotedChanges {
        if (limit !in 1..MAX_PAGE) {
            throw InvalidChangePageException("a page holds between 1 and $MAX_PAGE changes, asked for $limit")
        }
        if (afterOrdinal < 0) {
            throw InvalidChangePageException("changes are numbered from one, asked to start after $afterOrdinal")
        }

        val diff = comparisonFor(documentId, from, to)
        val changes = diffs.changesIn(diff.id, substantiveOnly, afterOrdinal, limit)

        return QuotedChanges(diff, quoted(diff, changes))
    }

    /**
     * The comparison the caller means: the pair they named, or the newest one recorded
     * for the document.
     *
     * A pair belonging to another document is refused as unknown rather than answered.
     * The identifiers are the caller's to guess, and confirming that two of them
     * belong together is an answer this endpoint has no reason to give.
     */
    private fun comparisonFor(
        documentId: DocumentId,
        from: DocumentVersionId?,
        to: DocumentVersionId?,
    ): VersionDiff {
        val diff = when {
            from != null && to != null -> diffs.comparisonOf(from, to, VersionComparison.READER_VERSION)
            from == null && to == null -> diffs.latestComparisonOf(documentId)
            else -> throw InvalidChangePageException("a pair is named by both versions or by neither")
        }

        return diff?.takeIf { it.documentId == documentId } ?: throw UnknownComparisonException("document $documentId")
    }

    private fun quoted(diff: VersionDiff, changes: List<UnitChange>): List<QuotedChange> {
        val pair = diffs.pairOf(diff.fromVersionId, diff.toVersionId)
        val before = pair?.fromTextHash?.let(::textOf)
        val after = pair?.toTextHash?.let(::textOf)

        return changes.map { change ->
            QuotedChange(
                change = change,
                before = before?.let { quoteOf(it, change.fromCharStart, change.fromCharEnd) },
                after = after?.let { quoteOf(it, change.toCharStart, change.toCharEnd) },
            )
        }
    }

    /**
     * A range read out of the text it was measured against.
     *
     * Bounds are checked even though they cannot be wrong: the text is addressed by the
     * hash of its own bytes, so it is the text the offsets came from. An out-of-range
     * quote would mean the archive had been rewritten underneath a stored claim, and
     * the honest answer to that is no quote rather than a substring of something else.
     */
    private fun quoteOf(text: String, start: Int?, end: Int?): String? =
        if (start != null && end != null && start >= 0 && end <= text.length && end > start) {
            text.substring(start, end)
        } else {
            null
        }

    private fun textOf(hash: ContentHash): String? =
        blobs.read(BlobBucket.DERIVED, hash)?.use { it.readBytes().toString(Charsets.UTF_8) }

    private companion object {
        /**
         * The most changes one request may ask for. Two hundred is more than a screen
         * and less than a document: paging exists because a redrafted bill has
         * thousands.
         */
        const val MAX_PAGE = 200
    }
}
