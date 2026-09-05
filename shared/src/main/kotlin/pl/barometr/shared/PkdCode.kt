package pl.barometr.shared

/**
 * A Polish industry code: `62`, `62.0`, `62.01`, `62.01.Z`.
 *
 * **Only the numeric levels.** A section is a letter — `J` — standing for a range of
 * divisions, and which divisions it covers is a fact about the classification that this
 * system does not hold: GUS publishes it as a web application rather than as data, and
 * PKD 2025 rearranged the sections again. Accepting `J` would mean either hard-coding a
 * range from memory or matching nothing, and the second is worse for being silent. A
 * division is the useful granularity anyway — a company says "we are in 62", not "we
 * are in J".
 *
 * **Coverage is by level, not by text.** The classification nests: division `62`,
 * group `62.0`, class `62.01`, subclass `62.01.Z`, each one digit longer than the last.
 * So containment is a comparison of the digits with the dots taken out, which is what
 * makes "a profile that said 62 hears about everything under it" a set of equality
 * lookups rather than a graph walk.
 *
 * The dots cannot be compared as text, and that is not a detail: `62.0` is written as a
 * prefix of `62.01` but `"62.0."` is not, so a `startsWith` on the printed form has a
 * group covering nothing at all. That is what this used to do, and what
 * `PkdCodeTest` now pins down in both directions.
 *
 * Lives in `shared` because two contexts need the same reading of it: profiles, where
 * somebody chooses codes, and taxonomy, where an act is tagged with them. A second
 * definition is a second answer to "is this act in my industry".
 */
@JvmInline
value class PkdCode(val value: String) {
    init {
        require(value.matches(PATTERN)) { "Not a PKD code: '$value'" }
    }

    /** `6201` for `62.01.Z` — the levels, without the punctuation that prints them. */
    private val digits: String get() = value.filter(Char::isDigit)

    /** `Z` for `62.01.Z`, null for anything above a subclass. */
    private val subclass: Char? get() = value.lastOrNull()?.takeIf { it.isLetter() }

    /**
     * True when [other] falls inside this code, including when they are the same.
     *
     * A subclass is a leaf: `41.20.Z` covers itself and nothing else, and in particular
     * not the class `41.20` it belongs to.
     */
    fun covers(other: PkdCode): Boolean = when {
        subclass != null -> other.value == value
        else -> other.digits.startsWith(digits)
    }

    /**
     * This code and every level above it: `62.01.Z` is also `62.01`, `62.0` and `62`.
     *
     * What turns "which profiles asked for this act's industry" into equality on an
     * indexed column: an act tagged `62.01.Z` is caught by a profile that chose any of
     * the four, and the expansion is done once per act rather than as a pattern match
     * against every interest ever stored.
     */
    fun ancestry(): List<PkdCode> = buildList {
        (DIVISION_DIGITS..digits.length).forEach { level -> add(PkdCode(printed(digits.take(level)))) }
        if (subclass != null) add(this@PkdCode)
    }

    override fun toString(): String = value

    companion object {
        /** `62`, `62.0`, `62.01`, `62.01.Z` — the letter is the subclass, not a section. */
        private val PATTERN = Regex("\\d{2}(\\.\\d)?(\\d)?(\\.[A-Z])?")

        private const val DIVISION_DIGITS = 2

        fun parseOrNull(value: String): PkdCode? =
            value.trim().uppercase().takeIf { it.matches(PATTERN) }?.let(::PkdCode)

        /** `6201` back to `62.01`, which is how the classification is written and stored. */
        private fun printed(digits: String): String = buildString {
            append(digits.take(DIVISION_DIGITS))
            if (digits.length > DIVISION_DIGITS) append(".${digits.substring(DIVISION_DIGITS)}")
        }
    }
}
