package pl.barometr.profiles.internal

/**
 * A Polish industry code: `62`, `62.01`, `62.01.Z`.
 *
 * **Only the numeric levels.** A section is a letter — `J` — standing for a range of
 * divisions, and which divisions it covers is a fact about the classification that
 * this system does not hold: GUS publishes it as a web application rather than as
 * data, and PKD 2025 rearranged the sections again. Accepting `J` would mean either
 * hard-coding a range from memory or matching nothing, and the second is worse for
 * being silent. A division is the useful granularity anyway — a company says "we are
 * in 62", not "we are in J".
 *
 * **Coverage is prefix, and that is not a shortcut.** The classification nests by
 * construction: 62.01.Z is inside 62.01, which is inside 62. So a profile that says
 * `62` is matched by anything tagged beneath it, without a dictionary and without a
 * join — which is what makes "change a PKD code and the matched set changes at once"
 * a single indexed query rather than a graph walk.
 */
@JvmInline
value class PkdCode(val value: String) {
    init {
        require(value.matches(PATTERN)) { "Not a PKD code: '$value'" }
    }

    /** Division, group, class or subclass — how specific this code is. */
    val level: Int get() = value.count { it == '.' } + 1

    /** True when [other] falls inside this code, including when they are the same. */
    fun covers(other: PkdCode): Boolean =
        other.value == value || other.value.startsWith("$value.")

    override fun toString(): String = value

    companion object {
        /** `62`, `62.0`, `62.01`, `62.01.Z` — the letter is the subclass, not a section. */
        private val PATTERN = Regex("\\d{2}(\\.\\d)?(\\d)?(\\.[A-Z])?")

        fun parseOrNull(value: String): PkdCode? =
            value.trim().uppercase().takeIf { it.matches(PATTERN) }?.let(::PkdCode)
    }
}
