package pl.barometr.corpus.api

import java.time.Instant

/**
 * What one comparison of two versions found, without the changes themselves.
 *
 * The header is what a card shows — "41 changes, three of them substantive, computed
 * on Tuesday" — and the changes are paged out of [DocumentDiffs] behind it. A
 * redrafted bill produces thousands, and loading them to count them is the read that
 * would make the card slow.
 *
 * [readerVersion] names the parser and the alignment that produced this. Improving
 * either does not rewrite history: the new reading is computed beside the old one, and
 * this stays a true account of what was said at the time.
 */
data class VersionDiff(
    val id: VersionDiffId,
    val documentId: DocumentId,
    val fromVersionId: DocumentVersionId,
    val toVersionId: DocumentVersionId,
    val readerVersion: Int,
    val unitsAdded: Int,
    val unitsRemoved: Int,
    val unitsModified: Int,
    val unitsMoved: Int,
    val substantiveChanges: Int,
    val computedAt: Instant,
) {
    val changeCount: Int get() = unitsAdded + unitsRemoved + unitsModified + unitsMoved
}
