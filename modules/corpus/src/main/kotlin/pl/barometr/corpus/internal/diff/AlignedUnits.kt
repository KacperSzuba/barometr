package pl.barometr.corpus.internal.diff

import pl.barometr.corpus.api.ChangeKind

/**
 * One unit of the older version paired with what became of it — or with nothing.
 *
 * The pair is what alignment decides; what *kind* of change it is follows from the
 * pair and is therefore asked of it rather than decided a second time somewhere else.
 *
 * [similarity] is null when the two were matched because their paths agreed. There was
 * no judgement to record: they are the same unit because the document says so.
 */
data class AlignedUnits(
    val before: UnitReading?,
    val after: UnitReading?,
    val similarity: Double?,
) {
    init {
        require(before != null || after != null) { "An alignment is about at least one unit" }
    }

    val kind: ChangeKind
        get() = when {
            after == null -> ChangeKind.REMOVED
            before == null -> ChangeKind.ADDED
            before.comparable == after.comparable -> ChangeKind.MOVED
            else -> ChangeKind.MODIFIED
        }

    /**
     * Whether this is a change a reader should be shown by default.
     *
     * A move is never substantive on its own — the words are identical, and the
     * renumbering is stated in the paths. A modification is substantive when the two
     * readings differ by more than punctuation and spacing. An addition or a removal is
     * substantive unless the unit says nothing but punctuation, which is what an empty
     * line left by a table looks like once it has been through a PDF.
     */
    val substantive: Boolean
        get() = when (kind) {
            ChangeKind.MOVED -> false
            ChangeKind.MODIFIED -> before?.core != after?.core
            ChangeKind.ADDED -> after?.core?.isNotEmpty() == true
            ChangeKind.REMOVED -> before?.core?.isNotEmpty() == true
        }
}
