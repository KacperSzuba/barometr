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
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

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

    /**
     * Every sitting, numbered or not. `number: 0` is the API's placeholder for a
     * sitting it has not numbered — the National Assembly, a ceremonial assembly, a
     * sitting still only planned — so it is read as the absence it is rather than as
     * a number eleven sittings share.
     */
    fun proceedings(term: Int): List<SejmProceeding> =
        readArray("/sejm/term$term/proceedings").map { node ->
            val number = node.path("number").takeIf { it.isInt }?.asInt()?.takeIf { it > 0 }
            val firstDate = node.path("dates").firstOrNull()?.asString()?.takeIf { it.isNotBlank() }
                ?.let(LocalDate::parse)

            SejmProceeding(
                number = number,
                firstDate = firstDate,
                entity = SejmEntity(number?.toString() ?: firstDate.toString(), node),
            )
        }

    /**
     * One page of the index of legislative processes.
     *
     * Paged, unlike everything else this API serves: `limit` and `offset` are honoured
     * here, and a term holds well over a thousand processes. The index carries no
     * stages, so it is navigation — what to fetch, and whether it is worth fetching.
     */
    fun processes(term: Int, offset: Int, limit: Int): List<SejmProcessSummary> =
        readArray("/sejm/term$term/processes?limit=$limit&offset=$offset").mapNotNull { node ->
            val number = node.path("number").asString()?.takeIf { it.isNotBlank() }
                ?: node.path("number").takeIf { it.isInt }?.asInt()?.toString()
                ?: return@mapNotNull null

            SejmProcessSummary(number, node.optionalTimestamp("changeDate"))
        }

    /** One process, with the stages the index leaves out. */
    fun process(term: Int, number: String): SejmEntity =
        SejmEntity(number, json.readTree(read("/sejm/term$term/processes/$number")))

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

    /**
     * Null rather than an exception: a stamp we cannot read costs the check that
     * decides whether a process is worth re-fetching, which then falls back to
     * fetching it — correct, only more expensive.
     */
    private fun JsonNode.optionalTimestamp(field: String): LocalDateTime? =
        path(field).asString()?.takeIf { it.isNotBlank() }?.let { stamp ->
            try {
                LocalDateTime.parse(stamp)
            } catch (malformed: DateTimeParseException) {
                null
            }
        }

    private fun JsonNode.optionalDate(field: String): LocalDate? =
        path(field).asString()?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)

    private companion object {
        const val TERMS = "/sejm/term"
    }
}
