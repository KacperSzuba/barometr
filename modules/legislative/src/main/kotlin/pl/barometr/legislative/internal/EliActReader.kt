package pl.barometr.legislative.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.barometr.shared.Eli
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Reads an archived ISAP act into the facts this context keeps.
 *
 * Reading happens here, out of the archive, rather than in the connector that fetched
 * it — the same division the corpus makes, and for the same reason: the graph has to
 * be rebuildable from stored bytes without asking ISAP for them again.
 *
 * Dates are the source's, with one substitution made deliberately. `announcementDate`
 * is the day the act is dated and is sometimes plainly wrong in the register — the
 * archive holds one dated 2206 — while `promulgation` is the day it appeared in the
 * journal, which is the fact this system reports and orders by.
 */
@Component
class EliActReader(private val json: ObjectMapper) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Null when the payload is not an act this context can key on. */
    fun read(payload: ByteArray): EliActMetadata? {
        val body = json.readTree(payload)
        val eli = body.path("ELI").asString()?.let(Eli::parseOrNull) ?: return null
        val title = body.path("title").asString()?.takeIf { it.isNotBlank() } ?: return null

        val references = mutableListOf<ActReferenceEdge>()
        val unmapped = mutableSetOf<String>()
        body.path("references").properties().forEach { (label, referenced) ->
            referenced.mapNotNull { Eli.parseOrNull(it.path("id").asString().orEmpty()) }
                .forEach { target ->
                    val edge = EliReferenceLabels.edgeOf(label, eli, target)
                    if (edge == null) unmapped += label else references += edge
                }
        }

        return EliActMetadata(
            eli = eli,
            title = title,
            type = body.path("type").asString()?.takeIf { it.isNotBlank() } ?: UNSTATED_TYPE,
            announcedOn = body.dateOf("promulgation"),
            inForceFrom = body.dateOf("entryIntoForce"),
            prints = body.path("prints").mapNotNull(::printOf),
            // Distinct, and only edges the source actually stated: a self-reference
            // would be rejected by the database, so it is dropped where the reason
            // can be seen rather than raised as a constraint violation.
            references = references.distinct().filterNot { it.from == it.to },
            unmappedLabels = unmapped.toList(),
        )
    }

    private fun printOf(node: JsonNode): SejmPrintReference? {
        val term = node.path("term").takeIf { it.isInt }?.asInt() ?: return null
        val number = node.path("number").asString()?.takeIf { it.isNotBlank() } ?: return null

        return SejmPrintReference(term, number)
    }

    private fun JsonNode.dateOf(field: String): LocalDate? =
        path(field).asString()?.takeIf { it.isNotBlank() }?.let { value ->
            try {
                LocalDate.parse(value)
            } catch (malformed: DateTimeParseException) {
                log.warn("Unreadable {} '{}' on act {}", field, value, path("ELI").asString())
                null
            }
        }

    private companion object {
        /** The register does state a type for every act; this is not silence made up. */
        const val UNSTATED_TYPE = "nieokreślony"
    }
}
