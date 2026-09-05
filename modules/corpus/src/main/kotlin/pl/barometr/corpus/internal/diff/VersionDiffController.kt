package pl.barometr.corpus.internal.diff

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.corpus.api.VersionDiff
import java.util.UUID

/**
 * What changed between two versions of a document.
 *
 * Any authenticated caller may read it, for the reason the draft card gives: this is
 * the product's reading of a public process. Queueing a comparison is an operator's,
 * because it is minutes of parsing over documents that can run to three hundred pages
 * — the same reason a backfill is not something anybody who signs up may start.
 *
 * Both sides of every change are quoted from the archive rather than described, and
 * every range in the response indexes the extracted text the quote came from. A client
 * that wants to highlight inside a unit subtracts the unit's own start; nothing here
 * pre-computes that, because a range that means one thing in the response and another
 * in the database is how a highlight ends up over the wrong sentence.
 */
@RestController
@RequestMapping("/api/v1/corpus/documents/{documentId}/changes")
class VersionDiffController(
    private val changes: ComparedChanges,
    private val diffs: VersionDiffRepository,
    private val queue: VersionDiffQueue,
) {

    @GetMapping
    fun changes(
        @PathVariable documentId: UUID,
        @RequestParam(required = false) from: UUID?,
        @RequestParam(required = false) to: UUID?,
        @RequestParam(defaultValue = "false") substantiveOnly: Boolean,
        @RequestParam(defaultValue = "0") after: Int,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ChangesResponse {
        val page = changes.changesOf(
            documentId = DocumentId(documentId),
            from = from?.let(::DocumentVersionId),
            to = to?.let(::DocumentVersionId),
            substantiveOnly = substantiveOnly,
            afterOrdinal = after,
            limit = limit,
        )

        return ChangesResponse(
            comparison = describe(page.diff),
            changes = page.changes.mapIndexed { index, quoted -> describe(quoted, after + index + 1) },
        )
    }

    /**
     * Queues whatever comparisons this document is missing.
     *
     * Idempotent twice over: a pair already queued is dropped by the dedup key, and one
     * already compared under the current reading is dropped by the unique index when
     * the job runs. So this is safe to press, and does nothing at all for a document
     * whose changes are already recorded.
     */
    @PostMapping("/queue")
    @PreAuthorize("hasRole('OPERATOR')")
    fun queueComparisons(@PathVariable documentId: UUID): QueuedResponse {
        val pairs = diffs.pairsOfDocument(DocumentId(documentId))

        return QueuedResponse(
            pairs = pairs.size,
            queued = pairs.count(queue::queueComparison),
        )
    }

    private fun describe(diff: VersionDiff) = ComparisonResponse(
        id = diff.id.value,
        documentId = diff.documentId.value,
        fromVersionId = diff.fromVersionId.value,
        toVersionId = diff.toVersionId.value,
        computedAt = diff.computedAt.toString(),
        changes = diff.changeCount,
        added = diff.unitsAdded,
        removed = diff.unitsRemoved,
        modified = diff.unitsModified,
        moved = diff.unitsMoved,
        substantive = diff.substantiveChanges,
    )

    private fun describe(quoted: QuotedChange, ordinal: Int) = ChangeResponse(
        ordinal = ordinal,
        kind = quoted.change.kind.wireName,
        unitKind = quoted.change.unitKind,
        substantive = quoted.change.substantive,
        renumbered = quoted.change.renumbered,
        fromPath = quoted.change.fromPath,
        toPath = quoted.change.toPath,
        fromCharStart = quoted.change.fromCharStart,
        fromCharEnd = quoted.change.fromCharEnd,
        toCharStart = quoted.change.toCharStart,
        toCharEnd = quoted.change.toCharEnd,
        similarity = quoted.change.similarity,
        before = quoted.before,
        after = quoted.after,
        words = quoted.change.words.map {
            WordResponse(it.kind.wireName, it.fromCharStart, it.fromCharEnd, it.toCharStart, it.toCharEnd)
        },
        wordsTruncated = quoted.change.wordsTruncated,
    )

    data class QueuedResponse(
        /** Adjacent pairs of versions this document has, both sides with text. */
        val pairs: Int,
        /** How many of them were not already queued or running. */
        val queued: Int,
    )

    data class ChangesResponse(val comparison: ComparisonResponse, val changes: List<ChangeResponse>)

    data class ComparisonResponse(
        val id: UUID,
        val documentId: UUID,
        val fromVersionId: UUID,
        val toVersionId: UUID,
        val computedAt: String,
        val changes: Int,
        val added: Int,
        val removed: Int,
        val modified: Int,
        val moved: Int,
        val substantive: Int,
    )

    data class ChangeResponse(
        /** Position in the comparison, numbered from one — what `?after=` pages by. */
        val ordinal: Int,
        val kind: String,
        val unitKind: String,
        val substantive: Boolean,
        val renumbered: Boolean,
        val fromPath: String?,
        val toPath: String?,
        val fromCharStart: Int?,
        val fromCharEnd: Int?,
        val toCharStart: Int?,
        val toCharEnd: Int?,
        val similarity: Double?,
        val before: String?,
        val after: String?,
        val words: List<WordResponse>,
        val wordsTruncated: Boolean,
    )

    data class WordResponse(
        val kind: String,
        val fromCharStart: Int?,
        val fromCharEnd: Int?,
        val toCharStart: Int?,
        val toCharEnd: Int?,
    )
}
