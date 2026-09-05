package pl.barometr.corpus.api

/**
 * Read port over what changed between versions of a document. Nothing outside corpus
 * touches its tables.
 *
 * Two ways in, because there are two questions. A card asks "what changed in the
 * newest revision of this bill" and gets the latest comparison; a reader following a
 * link asks about one pair and gets that one, whether or not it is the newest.
 *
 * The changes are paged separately from the header for the reason the header exists: a
 * redrafted bill has thousands of them, and neither the card nor the first screen of
 * the diff needs any but the first few.
 */
interface DocumentDiffs {

    /** The newest comparison recorded for this document, or null when it has one version. */
    fun latestComparisonOf(documentId: DocumentId): VersionDiff?

    fun comparisonOf(from: DocumentVersionId, to: DocumentVersionId): VersionDiff?

    /**
     * Changes in document order, starting after [afterOrdinal].
     *
     * Paged by ordinal rather than by offset: ordinals are dense and fixed at the
     * moment the comparison was recorded, so a page cannot shift under a reader.
     */
    fun changesIn(
        diff: VersionDiffId,
        substantiveOnly: Boolean,
        afterOrdinal: Int,
        limit: Int,
    ): List<UnitChange>
}
