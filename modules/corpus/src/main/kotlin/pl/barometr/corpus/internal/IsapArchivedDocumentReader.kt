package pl.barometr.corpus.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.shared.Eli
import pl.barometr.sources.api.ConnectorId
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Reads ISAP's archived acts.
 *
 * One shape, addressed by its ELI, which is why this reader is four lines of
 * substance where the Sejm's is a table: an act is an act, and the address is
 * already the canonical identifier.
 *
 * The published moment is `promulgation` — the day the act appeared in the journal —
 * and not `announcementDate`, the day it was signed. The two differ by weeks, and
 * the archive holds acts whose announcement date is plainly mistyped (one is dated
 * 2206), which is the sort of thing a derived model should not quietly inherit.
 */
@Component
class IsapArchivedDocumentReader(private val json: ObjectMapper) : ArchivedDocumentReader {

    private val log = LoggerFactory.getLogger(javaClass)

    override val connectorId = ConnectorId("isap")

    override fun describe(externalId: ExternalId, payload: ByteArray): DocumentDescriptor {
        if (Eli.parseOrNull(externalId.value) == null) {
            log.warn("ISAP document '{}' is not addressed by an ELI", externalId)
            return DocumentDescriptor(DocumentKind.UNKNOWN, null, null)
        }

        val body = json.readTree(payload)

        return DocumentDescriptor(
            kind = ACT,
            title = body.path("title").asString()?.takeIf { it.isNotBlank() },
            publishedAt = body.path("promulgation").asString()?.let(::dayOf),
        )
    }

    /** A day becomes midnight UTC; the journal publishes days, not moments. */
    private fun dayOf(value: String): Instant? =
        try {
            LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant()
        } catch (malformed: DateTimeParseException) {
            log.warn("Unreadable promulgation date '{}'", value)
            null
        }

    private companion object {
        val ACT = DocumentKind("act")
    }
}
