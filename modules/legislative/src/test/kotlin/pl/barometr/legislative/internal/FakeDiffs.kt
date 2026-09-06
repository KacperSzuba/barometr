package pl.barometr.legislative.internal

import pl.barometr.corpus.api.DocumentDiffs
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.corpus.api.UnitChange
import pl.barometr.corpus.api.VersionDiff
import pl.barometr.corpus.api.VersionDiffId
import pl.barometr.shared.Ids
import java.time.Instant

/**
 * Corpus's account of what changed, as much of it as a card asks for: the newest
 * comparison of a document, or nothing.
 *
 * The changes themselves are never read here — a card shows the header and pages the
 * rest out of corpus — so the paging method answers with nothing rather than pretending.
 */
class FakeDiffs : DocumentDiffs {
    private val latest = mutableMapOf<DocumentId, VersionDiff>()

    fun compared(documentId: DocumentId, changes: Int, substantive: Int, at: Instant): VersionDiff {
        val diff = VersionDiff(
            id = VersionDiffId(Ids.next()),
            documentId = documentId,
            fromVersionId = DocumentVersionId(Ids.next()),
            toVersionId = DocumentVersionId(Ids.next()),
            readerVersion = 1,
            unitsAdded = changes,
            unitsRemoved = 0,
            unitsModified = 0,
            unitsMoved = 0,
            substantiveChanges = substantive,
            computedAt = at,
        )
        latest[documentId] = diff

        return diff
    }

    override fun latestComparisonOf(documentId: DocumentId): VersionDiff? = latest[documentId]

    override fun comparisonOf(from: DocumentVersionId, to: DocumentVersionId): VersionDiff? =
        latest.values.firstOrNull { it.fromVersionId == from && it.toVersionId == to }

    override fun changesIn(
        diff: VersionDiffId,
        substantiveOnly: Boolean,
        afterOrdinal: Int,
        limit: Int,
    ): List<UnitChange> = emptyList()
}
