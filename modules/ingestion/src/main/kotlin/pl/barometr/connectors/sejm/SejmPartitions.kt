package pl.barometr.connectors.sejm

/** Backfill partition keys, and the term they stand for. */
object SejmPartitions {
    private const val PREFIX = "term"

    fun of(term: Int): String = "$PREFIX$term"

    fun termOf(partitionKey: String): Int =
        partitionKey.removePrefix(PREFIX).toIntOrNull()
            ?: error("Malformed partition key '$partitionKey'")

    fun label(term: SejmTerm): String =
        "Kadencja ${term.number} (${term.from ?: "?"} – ${term.to ?: "trwa"})"
}
