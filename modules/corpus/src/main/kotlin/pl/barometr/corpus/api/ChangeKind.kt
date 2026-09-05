package pl.barometr.corpus.api

/**
 * What happened to one editorial unit between two versions.
 *
 * Four outcomes, and the fourth is the reason this feature exists. Compared as text, a
 * unit that kept its words and changed its number is a deletion and an insertion; a
 * three-hundred-page bill renumbered after one insertion is then reported as having
 * changed entirely, which is the same as not being reported at all.
 *
 * A unit that changed *both* its number and its words is [MODIFIED]: the renumbering is
 * visible in the pair of paths the change carries, and calling it a move would hide
 * that its words are different.
 */
enum class ChangeKind(val wireName: String) {
    /** Written for the first time in the newer version. */
    ADDED("added"),

    /** Gone from the newer version, and nothing in it says what this said. */
    REMOVED("removed"),

    /** The same unit, saying something different. */
    MODIFIED("modified"),

    /** The same words, in a different place — renumbered, or moved to another chapter. */
    MOVED("moved"),
    ;

    companion object {
        fun of(wireName: String): ChangeKind? = entries.firstOrNull { it.wireName == wireName }
    }
}
