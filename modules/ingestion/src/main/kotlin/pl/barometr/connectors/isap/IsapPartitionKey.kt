package pl.barometr.connectors.isap

/**
 * Which journal and which year one backfill partition covers.
 *
 * A publisher-year is the unit the ELI API can be walked independently: every
 * search is scoped by exactly that pair, and the API states a total for it — which
 * is also the figure the completeness audit needs.
 *
 * The type *is* the key format, parsed and rendered in one place. Spelled out at
 * each call site instead, one method writing `DU-1918` where another expects
 * `DU/1918` would produce a partition that resumes from zero forever.
 */
data class IsapPartitionKey(val publisher: String, val year: Int) {

    override fun toString(): String = "$publisher$SEPARATOR$year"

    companion object {
        private const val SEPARATOR = "/"

        fun parse(key: String): IsapPartitionKey {
            val parts = key.split(SEPARATOR)
            val year = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size != 2 || year == null) error("Malformed partition key '$key'")

            return IsapPartitionKey(parts[0], year)
        }
    }
}
