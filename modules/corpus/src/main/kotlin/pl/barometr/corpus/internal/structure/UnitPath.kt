package pl.barometr.corpus.internal.structure

/**
 * Where a unit sits in the document, written the way it is cited: `art-12a/ust-2/pkt-3`.
 *
 * This is the tree, not a summary of it. A parent is a prefix of its children, so
 * "everything under article 12a" is a string comparison, an index can answer it, and
 * nothing has to hold a graph in memory to walk a three-hundred-page bill. A nested
 * object model would have to be flattened into exactly this for every query and every
 * response anyway.
 *
 * The path is also what makes renumbering visible: a unit that kept its text and
 * changed its path was renumbered, and the pair of paths is the whole evidence for
 * saying so.
 */
@JvmInline
value class UnitPath(val value: String) {
    init {
        require(value.matches(PATTERN)) { "Not a unit path: '$value'" }
    }

    /** The path of a unit written under this one. */
    fun child(kind: UnitKind, designator: String): UnitPath =
        UnitPath("$value/${kind.wireName}-${designator.lowercase()}")

    /** True when [other] is this unit or is written under it. */
    fun covers(other: UnitPath): Boolean =
        other.value == value || other.value.startsWith("$value/")

    override fun toString(): String = value

    companion object {
        private val SEGMENT = "[a-z]+(-[a-z0-9]+)?"
        private val PATTERN = Regex("$SEGMENT(/$SEGMENT)*")

        /** The path of a unit written at the top of the document, under no other. */
        fun root(kind: UnitKind, designator: String?): UnitPath =
            UnitPath(designator?.let { "${kind.wireName}-${it.lowercase()}" } ?: kind.wireName)
    }
}
