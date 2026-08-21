package pl.barometr.shared

/**
 * European Legislation Identifier, in the short form the Polish journals use:
 * `DU/2024/1222` — journal, year, position.
 *
 * The canonical identity of a published act, and the one identifier three contexts
 * agree on: ingestion addresses an archived act by it, corpus stores it as the
 * document's external id, and legislative keys the act itself on it. That is why it
 * lives here rather than in any one of them — it is a value type they trade in, not
 * a concept any single context owns.
 *
 * The full ELI is a URI (`http://eli.gov.pl/eli/DU/2024/1222/ogl`); this is the
 * fragment that identifies the act, which is also the form the API returns and the
 * form a person recognises.
 */
@JvmInline
value class Eli(val value: String) {
    init {
        require(value.matches(PATTERN)) { "Not an ELI address: '$value'" }
    }

    /** `DU` or `MP`: which journal published it. */
    val publisher: String get() = value.substringBefore(SEPARATOR)

    override fun toString(): String = value

    companion object {
        private const val SEPARATOR = '/'

        /**
         * Two to four capitals for the journal, a four-digit year, a position. Wide
         * enough for `DU` and `MP` without admitting a path or a URL, which is what
         * a source is most likely to put in this field the day it changes shape.
         */
        private val PATTERN = Regex("[A-Z]{2,4}/\\d{4}/\\d{1,6}")

        fun of(publisher: String, year: Int, position: Int): Eli =
            Eli("$publisher$SEPARATOR$year$SEPARATOR$position")

        /**
         * Null rather than an exception, for text a source controls: a malformed
         * address arriving from outside is a schema warning for the caller to record,
         * not a failure of this type's contract.
         */
        fun parseOrNull(value: String): Eli? = if (value.matches(PATTERN)) Eli(value) else null
    }
}
