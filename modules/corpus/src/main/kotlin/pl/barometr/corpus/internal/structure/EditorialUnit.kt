package pl.barometr.corpus.internal.structure

/**
 * One editorial unit as it stands in a version's extracted text.
 *
 * **The span is the unit's own words, not its children's.** An article that contains
 * five points spans only its lead-in sentence; each point spans itself. That is what
 * keeps a change inside one point from being reported as a change to the whole
 * article — and the article's full extent is still recoverable, because its children
 * are exactly the units whose path it covers.
 *
 * Offsets index the text the corpus stored, so `text.substring(charStart, charEnd)` is
 * what a citation renders. Nothing here holds a copy of that text.
 */
data class EditorialUnit(
    val kind: UnitKind,
    val path: UnitPath,
    /** `12a`, `2`, `b` — null for the preamble, which nobody numbers. */
    val designator: String?,
    val charStart: Int,
    val charEnd: Int,
) {
    init {
        require(charStart >= 0) { "A unit starts inside the text, got $charStart" }
        require(charEnd > charStart) { "A unit spans at least one character, got $charStart..$charEnd" }
    }

    fun textIn(text: String): String = text.substring(charStart, charEnd)
}
