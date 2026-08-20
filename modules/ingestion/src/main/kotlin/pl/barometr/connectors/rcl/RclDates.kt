package pl.barometr.connectors.rcl

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * RPL writes dates two different ways, and both are parsed here rather than at the
 * call sites that happen to need one.
 *
 * The difference is not cosmetic. Listings and project cards carry `17-08-2026` —
 * day resolution, day-first — while the change registers carry `2026-04-09 15:14`,
 * accurate to the minute. That second form is what makes the registers worth
 * fetching at all: a stage transition timed to the minute can fill the `valid_from`
 * of a bitemporal record, where a bare date can only say "some time that day".
 *
 * Both return null rather than throwing. A date that fails to parse is one field of
 * one archived page; refusing the whole document over it would turn a cosmetic
 * change on the site into an ingestion outage.
 */
object RclDates {

    private val DAY_FIRST: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** `17-08-2026`, as printed on listings and project cards. */
    fun readDate(text: String?): LocalDate? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return try {
            LocalDate.parse(trimmed, DAY_FIRST)
        } catch (malformed: DateTimeParseException) {
            null
        }
    }

    /** `2026-04-09 15:14`, as printed in the change registers. */
    fun readTimestamp(text: String?): LocalDateTime? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return try {
            LocalDateTime.parse(trimmed, TIMESTAMP)
        } catch (malformed: DateTimeParseException) {
            null
        }
    }
}
