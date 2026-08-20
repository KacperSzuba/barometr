package pl.barometr.connectors.sejm

import pl.barometr.ingestion.api.SourceAccessDeniedException
import pl.barometr.ingestion.api.SourceFetchException
import pl.barometr.http.HttpFetch
import pl.barometr.http.HttpOutcome
import pl.barometr.http.SourceHttpClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.LocalDate

/**
 * One entity exactly as the Sejm API returned it.
 *
 * The body stays wrapped: callers get the key they need and hand the whole thing
 * to a canonicaliser, so nothing outside this file navigates a `JsonNode`.
 */
class SejmEntity internal constructor(
    /** The source's own identifier: a print number, a club symbol, an MP id. */
    val naturalKey: String,
    internal val body: JsonNode,
)

/** A sitting, plus the number its votings hang off. */
class SejmProceeding internal constructor(
    val number: Int,
    val entity: SejmEntity,
)

class SejmTerm internal constructor(
    val number: Int,
    val isCurrent: Boolean,
    val from: LocalDate?,
    val to: LocalDate?,
    /**
     * When prints in this term last changed, as the API reports it. The whole
     * incremental strategy rests on this one field.
     */
    val printsLastChangedAt: String?,
    /** The source's own tally, for checking a finished backfill against. */
    val printCount: Int,
) {
    fun overlaps(windowStart: LocalDate, windowEnd: LocalDate): Boolean {
        val start = from ?: return false
        // An ongoing term runs to today, so it always reaches the window's end.
        val end = to ?: LocalDate.MAX
        return !start.isAfter(windowEnd) && !end.isBefore(windowStart)
    }
}

/**
 * Typed access to the Sejm API.
 *
 * Everything specific to *how* this source speaks lives here: URL shapes, JSON
 * field names, the fact that `limit` is ignored and that collections arrive
 * whole. Callers see lists of typed values and never a status code or a JSON node,
 * which is what keeps the connector readable as a description of the process
 * rather than as a parser.
 */
class SejmApiClient(
    private val httpClient: SourceHttpClient,
    private val baseUrl: URI,
    private val json: ObjectMapper,
) {

    fun terms(): List<SejmTerm> =
        readArray(TERMS).map { node ->
            SejmTerm(
                number = node.requireInt("num"),
                isCurrent = node.path("current").asBoolean(),
                from = node.optionalDate("from"),
                to = node.optionalDate("to"),
                printsLastChangedAt = node.path("prints").path("lastChanged").asString(),
                printCount = node.path("prints").path("count").asInt(0),
            )
        }

    fun currentTerm(): SejmTerm? =
        terms().let { terms -> terms.firstOrNull { it.isCurrent } ?: terms.maxByOrNull { it.number } }

    fun prints(term: Int): List<SejmEntity> = entities("/sejm/term$term/prints", "number")

    fun clubs(term: Int): List<SejmEntity> = entities("/sejm/term$term/clubs", "id")

    fun members(term: Int): List<SejmEntity> = entities("/sejm/term$term/MP", "id")

    fun proceedings(term: Int): List<SejmProceeding> =
        readArray("/sejm/term$term/proceedings").mapNotNull { node ->
            val number = node.path("number").takeIf { it.isInt }?.asInt() ?: return@mapNotNull null
            SejmProceeding(number, SejmEntity(number.toString(), node))
        }

    fun votings(term: Int, proceeding: Int): List<SejmEntity> =
        entities("/sejm/term$term/votings/$proceeding", "votingNumber")

    private fun entities(path: String, keyField: String): List<SejmEntity> =
        readArray(path).mapNotNull { node ->
            val key = node.path(keyField).asString()?.takeIf { it.isNotBlank() }
                ?: node.path(keyField).takeIf { it.isInt }?.asInt()?.toString()
                ?: return@mapNotNull null
            SejmEntity(key, node)
        }

    private fun readArray(path: String): List<JsonNode> {
        val body = read(path)
        val tree = json.readTree(body)
        require(tree.isArray) { "Expected an array from $path" }
        return tree.toList()
    }

    private fun read(path: String): ByteArray =
        when (val outcome = httpClient.fetch(HttpFetch(baseUrl.resolve(path)))) {
            is HttpOutcome.Fetched -> outcome.body
            is HttpOutcome.Refused -> throw SourceAccessDeniedException(path, outcome.detail)
            // A failure, not a bug: the queue retries it with backoff.
            is HttpOutcome.Failed -> throw SourceFetchException(path, outcome.detail)
            // Only sent for a conditional request, and this API supports none.
            HttpOutcome.NotModified -> throw SourceFetchException(path, "unexpected 304")
        }

    private fun JsonNode.requireInt(field: String): Int =
        path(field).takeIf { it.isInt }?.asInt() ?: error("Missing integer field '$field'")

    private fun JsonNode.optionalDate(field: String): LocalDate? =
        path(field).asString()?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)

    private companion object {
        const val TERMS = "/sejm/term"
    }
}
