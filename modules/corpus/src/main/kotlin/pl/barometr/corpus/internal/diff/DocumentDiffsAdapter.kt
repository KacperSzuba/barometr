package pl.barometr.corpus.internal.diff

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.barometr.corpus.api.DocumentDiffs
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.corpus.api.UnitChange
import pl.barometr.corpus.api.VersionDiff
import pl.barometr.corpus.api.VersionDiffId

/**
 * The context's read port over comparisons. Everything crossing the boundary is a
 * value type; a jOOQ record leaving here would take the schema with it.
 *
 * The reading a caller gets is the current one. Older readings stay in the table as an
 * account of what was said at the time, and are reachable by naming the pair they
 * belong to — but nothing outside this context should have to know a reading exists.
 */
@Component
@Transactional(readOnly = true)
class DocumentDiffsAdapter(private val diffs: VersionDiffRepository) : DocumentDiffs {

    override fun latestComparisonOf(documentId: DocumentId): VersionDiff? =
        diffs.latestComparisonOf(documentId)

    override fun comparisonOf(from: DocumentVersionId, to: DocumentVersionId): VersionDiff? =
        diffs.comparisonOf(from, to, VersionComparison.READER_VERSION)

    override fun changesIn(
        diff: VersionDiffId,
        substantiveOnly: Boolean,
        afterOrdinal: Int,
        limit: Int,
    ): List<UnitChange> = diffs.changesIn(diff, substantiveOnly, afterOrdinal, limit)
}
