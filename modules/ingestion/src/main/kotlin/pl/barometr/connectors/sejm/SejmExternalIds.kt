package pl.barometr.connectors.sejm

import pl.barometr.ingestion.api.ExternalId

/**
 * How a Sejm entity is addressed in our archive.
 *
 * In one place because these strings are a contract, not formatting: they are the
 * idempotency key, so changing one silently re-ingests every document of that kind
 * as if it were new. Built here rather than inline at six call sites, where a
 * single divergent `"term$term/print/$n"` would be invisible.
 */
object SejmExternalIds {

    fun print(term: Int, number: String): ExternalId = ExternalId("term$term/print/$number")

    fun club(term: Int, symbol: String): ExternalId = ExternalId("term$term/club/$symbol")

    fun member(term: Int, id: String): ExternalId = ExternalId("term$term/mp/$id")

    fun proceeding(term: Int, number: Int): ExternalId = ExternalId("term$term/proceeding/$number")

    fun voting(term: Int, proceeding: Int, votingNumber: String): ExternalId =
        ExternalId("term$term/proceeding/$proceeding/voting/$votingNumber")

    // Prefixes for counting an archive. Stated outright rather than derived from an
    // id by string surgery, which is how `removeSuffix("0")` ended up in the
    // completeness check.
    fun printPrefix(term: Int): String = "term$term/print/"

    fun proceedingPrefix(term: Int): String = "term$term/proceeding/"
}

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
