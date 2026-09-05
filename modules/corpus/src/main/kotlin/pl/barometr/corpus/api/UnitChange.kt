package pl.barometr.corpus.api

/**
 * One editorial unit's fate between two versions, with both sides addressed by
 * character range.
 *
 * [unitKind] is the vocabulary the paths are written in — `art`, `ust`, `pkt`, `lit`,
 * `tir`, `preambula` and the divisions. Carried as a name rather than recovered from
 * the path, because "three articles were removed" and "three tirets were removed" are
 * different news and neither should require a consumer to parse a string.
 *
 * [substantive] is false when the two readings differ only in whitespace, punctuation
 * or the designator. It is the flag a reader filters on: four hundred editorial
 * corrections hiding three real changes is how a diff view becomes unused.
 *
 * [similarity] is how sure the alignment was, and is null exactly when the paths
 * matched — there was nothing to be unsure about.
 */
data class UnitChange(
    val kind: ChangeKind,
    val unitKind: String,
    val substantive: Boolean,
    val fromPath: String?,
    val fromCharStart: Int?,
    val fromCharEnd: Int?,
    val toPath: String?,
    val toCharStart: Int?,
    val toCharEnd: Int?,
    val similarity: Double?,
    val words: List<WordChange>,
    /**
     * Set when a unit changed so completely that listing its words would be listing the
     * unit twice. [words] then holds the whole-unit range instead of hundreds of runs.
     */
    val wordsTruncated: Boolean,
) {
    init {
        require((fromPath == null) == (fromCharStart == null)) { "A side is a path and a range together" }
        require((toPath == null) == (toCharStart == null)) { "A side is a path and a range together" }
        when (kind) {
            ChangeKind.ADDED -> require(fromPath == null && toPath != null) { "An addition has only a new side" }
            ChangeKind.REMOVED -> require(fromPath != null && toPath == null) { "A removal has only an old side" }
            else -> require(fromPath != null && toPath != null) { "$kind has both sides" }
        }
        require(similarity == null || similarity in 0.0..1.0) { "Similarity is a fraction, got $similarity" }
    }

    /** True when the unit is in a different place in the newer version, whatever else changed. */
    val renumbered: Boolean get() = fromPath != null && toPath != null && fromPath != toPath
}
