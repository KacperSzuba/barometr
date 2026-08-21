package pl.barometr.legislative.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.barometr.shared.Eli
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Reads an archived legislative process into the facts this context keeps.
 *
 * Out of the archive rather than from the connector, like everything else derived
 * here: the path has to be rebuildable from stored bytes, without asking the Sejm for
 * a decade of processes again.
 */
@Component
class SejmProcessReader(private val json: ObjectMapper) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Null when the payload does not describe a process this model can key on. */
    fun read(payload: ByteArray): SejmProcessRecord? {
        val body = json.readTree(payload)
        val number = body.path("number").asString()?.takeIf { it.isNotBlank() } ?: return null
        val title = body.path("title").asString()?.takeIf { it.isNotBlank() } ?: return null
        val term = body.path("term").takeIf { it.isInt }?.asInt() ?: return null

        val closedOn = body.dateOf("closureDate")

        return SejmProcessRecord(
            printNumber = number,
            term = term,
            title = title,
            initiator = DraftInitiator.of(title),
            // The register's own classification. `documentType` is prose and varies;
            // the enum beside it is the closed set.
            isDraft = body.path("documentTypeEnum").asString() in DRAFT_TYPES,
            eli = body.path("ELI").asString()?.let(Eli::parseOrNull),
            rclNumber = body.path("rclNum").asString()?.takeIf { it.isNotBlank() },
            startedOn = body.dateOf("processStartDate"),
            closedOn = closedOn,
            // A verdict only once the register has closed the process. `passed` on an
            // open process is the answer to a question nobody asked yet.
            outcome = closedOn?.let {
                if (body.path("passed").asBoolean()) DraftOutcome.ENACTED else DraftOutcome.REJECTED
            },
            stages = body.path("stages").mapIndexed { ordinal, stage -> stageOf(ordinal, stage) },
        )
    }

    private fun stageOf(ordinal: Int, node: JsonNode): SejmProcessStage {
        val label = node.path("stageName").asString()?.takeIf { it.isNotBlank() }.orEmpty()

        return SejmProcessStage(
            ordinal = ordinal,
            date = node.dateOf("date"),
            stage = SejmStageVocabulary.stageOf(node.path("stageType").asString(), label),
            sourceLabel = label,
        )
    }

    private fun JsonNode.dateOf(field: String): LocalDate? =
        path(field).asString()?.takeIf { it.isNotBlank() }?.let { value ->
            try {
                LocalDate.parse(value)
            } catch (malformed: DateTimeParseException) {
                log.warn("Unreadable {} '{}' on process {}", field, value, path("number").asString())
                null
            }
        }

    private companion object {
        /** Bills and resolutions are drafts; motions and candidate lists are not. */
        val DRAFT_TYPES = setOf("BILL", "DRAFT_RESOLUTION")
    }
}
