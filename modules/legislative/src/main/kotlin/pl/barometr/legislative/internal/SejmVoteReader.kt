package pl.barometr.legislative.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Reads an archived voting into the facts this context keeps.
 *
 * Out of the archive rather than from the connector, like everything else derived here:
 * every vote of every sitting has to be rebuildable from stored bytes, without asking
 * the Sejm for a decade of sittings again.
 */
@Component
class SejmVoteReader(private val json: ObjectMapper) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Null when the payload does not describe a voting this model can key on. */
    fun read(payload: ByteArray): SejmVoteRecord? {
        val body = json.readTree(payload)

        val term = body.intOf("term") ?: return null
        val sitting = body.intOf("sitting") ?: return null
        val votingNumber = body.intOf("votingNumber") ?: return null
        val takenAt = body.momentOf("date") ?: return null

        // The item is what carries the prints, so a vote without one cannot be tied to
        // anything and cannot be described to a reader either.
        val title = body.text("title") ?: return null

        return SejmVoteRecord(
            term = term,
            sitting = sitting,
            votingNumber = votingNumber,
            sittingDay = body.intOf("sittingDay"),
            takenAt = takenAt,
            title = title,
            // `topic` is the vote, `description` the item it sits under; where both are
            // given the first is the narrower and the one worth showing.
            subject = body.text("topic") ?: body.text("description"),
            method = body.text("kind") ?: UNSTATED_METHOD,
            majority = body.text("majorityType"),
            majorityVotes = body.intOf("majorityVotes"),
            yes = body.countOf("yes"),
            no = body.countOf("no"),
            abstained = body.countOf("abstain"),
            notParticipating = body.countOf("notParticipating"),
            totalVoted = body.countOf("totalVoted"),
            citedPrints = CitedPrints.addressesIn(title, term),
        )
    }

    private fun JsonNode.text(field: String): String? =
        path(field).asString()?.takeIf { it.isNotBlank() }

    private fun JsonNode.intOf(field: String): Int? = path(field).takeIf { it.isInt }?.asInt()

    /**
     * A count the register omits is zero rather than unknown: it reports the tallies of
     * a vote it held, and a missing `abstain` on a vote nobody abstained from is the
     * register leaving out a zero, not withholding a number.
     */
    private fun JsonNode.countOf(field: String): Int = intOf(field)?.coerceAtLeast(0) ?: 0

    /** Sittings are timed in local time, without an offset, like every other Sejm moment. */
    private fun JsonNode.momentOf(field: String): Instant? =
        text(field)?.let { value ->
            try {
                LocalDateTime.parse(value).toInstant(ZoneOffset.UTC)
            } catch (malformed: DateTimeParseException) {
                log.warn("Unreadable timestamp '{}' in field '{}' of a voting", value, field)
                null
            }
        }

    private companion object {
        /**
         * A vote whose method the register did not state. Recorded as a word rather than
         * as null so that the column stays `NOT NULL` and the gap is visible in the data
         * rather than only in its absence.
         */
        const val UNSTATED_METHOD = "UNSTATED"
    }
}
