package pl.barometr.corpus.internal

import org.springframework.stereotype.Component
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.sources.api.ConnectorId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Reads the Sejm's archived entities.
 *
 * The kind comes from the external id and the title from the payload, because that
 * is how the two sources of truth actually divide: the id says what was addressed,
 * the body says what it is called. Each shape names its own title and date field —
 * the API has no common envelope, so a print is titled by `title`, a member by
 * `firstLastName` and a club by `name`.
 */
@Component
class SejmArchivedDocumentReader(private val json: ObjectMapper) : ArchivedDocumentReader {

    override val connectorId = ConnectorId("sejm")

    override fun describe(externalId: ExternalId, payload: ByteArray): DocumentDescriptor {
        val body = json.readTree(payload)

        return when (val id = externalId.value) {
            in PRINT -> DocumentDescriptor(PRINT_KIND, body.text("title"), body.day("documentDate"))
            in VOTING -> DocumentDescriptor(VOTING_KIND, body.text("description"), body.moment("date"))
            in PROCEEDING -> DocumentDescriptor(PROCEEDING_KIND, body.text("title"), body.firstDay("dates"))
            in CLUB -> DocumentDescriptor(CLUB_KIND, body.text("name"), null)
            in MEMBER -> DocumentDescriptor(MEMBER_KIND, body.text("firstLastName"), null)
            else -> unrecognised(id)
        }
    }

    private fun unrecognised(id: String) = DocumentDescriptor(DocumentKind.UNKNOWN, null, null)
        .also { log.warn("Sejm document '{}' matches no known address shape", id) }

    private fun JsonNode.text(field: String): String? =
        path(field).asString()?.takeIf { it.isNotBlank() }

    /**
     * A day becomes midnight UTC. The column is a `timestamptz` because most sources
     * give a moment, and a source that gives only a date should not be made to look
     * as though it gave more than it did — the day is the fact, and this is the one
     * conversion of it, stated once.
     */
    private fun JsonNode.day(field: String): Instant? =
        text(field)?.let { value ->
            try {
                LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant()
            } catch (malformed: DateTimeParseException) {
                log.warn("Unreadable date '{}' in field '{}'", value, field)
                null
            }
        }

    private fun JsonNode.moment(field: String): Instant? =
        text(field)?.let { value ->
            try {
                LocalDateTime.parse(value).toInstant(ZoneOffset.UTC)
            } catch (malformed: DateTimeParseException) {
                log.warn("Unreadable timestamp '{}' in field '{}'", value, field)
                null
            }
        }

    /** A sitting runs over several days; the first of them is when it began. */
    private fun JsonNode.firstDay(field: String): Instant? =
        path(field).firstOrNull()?.asString()?.let { first ->
            try {
                LocalDate.parse(first).atStartOfDay(ZoneOffset.UTC).toInstant()
            } catch (malformed: DateTimeParseException) {
                log.warn("Unreadable date '{}' in field '{}'", first, field)
                null
            }
        }

    private operator fun Regex.contains(value: String): Boolean = matches(value)

    private companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(SejmArchivedDocumentReader::class.java)

        // The archive's addressing contract, stated in `SejmExternalIds` on the
        // ingestion side. Duplicated here deliberately: see ArchivedDocumentReader.
        val PRINT = Regex("term\\d+/print/.+")
        val CLUB = Regex("term\\d+/club/.+")
        val MEMBER = Regex("term\\d+/mp/.+")
        val PROCEEDING = Regex("term\\d+/proceeding/\\d+")
        val VOTING = Regex("term\\d+/proceeding/\\d+/voting/.+")

        val PRINT_KIND = DocumentKind("print")
        val CLUB_KIND = DocumentKind("club")
        val MEMBER_KIND = DocumentKind("mp")
        val PROCEEDING_KIND = DocumentKind("proceeding")
        val VOTING_KIND = DocumentKind("voting")
    }
}
