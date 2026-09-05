package pl.barometr.corpus.api

/**
 * One run of words that changed inside a modified unit, addressed on both sides.
 *
 * Ranges rather than text, like everything else derived here: the words are read back
 * out of the version they belong to, so a highlighted phrase is what the archive holds
 * and not a copy that can drift from it.
 *
 * A pure insertion has no older range and a pure deletion has no newer one; a
 * replacement has both, which is what lets a reader see `14 dni` become `30 dni`
 * rather than one deletion followed by one insertion.
 */
data class WordChange(
    val kind: ChangeKind,
    val fromCharStart: Int?,
    val fromCharEnd: Int?,
    val toCharStart: Int?,
    val toCharEnd: Int?,
) {
    init {
        require(kind != ChangeKind.MOVED) { "Words are added, removed or replaced; they do not move" }
        require((fromCharStart == null) == (fromCharEnd == null)) { "A range needs both ends" }
        require((toCharStart == null) == (toCharEnd == null)) { "A range needs both ends" }
        require(fromCharStart != null || toCharStart != null) { "A word change touches at least one side" }
    }
}
