package pl.barometr.profiles.internal

/**
 * A place, in the identifier the Polish statistical office gives it: `14` for
 * Mazowieckie, `1465` for Warszawa, `1465011` for one of its districts.
 *
 * Nests by prefix like PKD and for the same reason — TERYT is built that way, a
 * powiat's code opening with its voivodeship's — so "everything in Mazowieckie"
 * needs no dictionary of what is in Mazowieckie.
 *
 * The whole country is expressed by having no region at all rather than by a code for
 * it: a profile with no place said nothing about place, and inventing a code for
 * "everywhere" would make that indistinguishable from somebody who chose it.
 */
@JvmInline
value class TerytCode(val value: String) {
    init {
        require(value.matches(PATTERN)) { "Not a TERYT code: '$value'" }
    }

    val level: Level
        get() = when (value.length) {
            VOIVODESHIP_DIGITS -> Level.VOIVODESHIP
            COUNTY_DIGITS -> Level.COUNTY
            else -> Level.MUNICIPALITY
        }

    fun covers(other: TerytCode): Boolean = other.value.startsWith(value)

    override fun toString(): String = value

    enum class Level { VOIVODESHIP, COUNTY, MUNICIPALITY }

    companion object {
        private const val VOIVODESHIP_DIGITS = 2
        private const val COUNTY_DIGITS = 4

        /** Two digits, four, or seven — the three levels TERYT actually addresses. */
        private val PATTERN = Regex("\\d{2}|\\d{4}|\\d{7}")

        fun parseOrNull(value: String): TerytCode? =
            value.trim().takeIf { it.matches(PATTERN) }?.let(::TerytCode)
    }
}
