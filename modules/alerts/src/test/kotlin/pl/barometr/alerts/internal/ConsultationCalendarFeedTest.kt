package pl.barometr.alerts.internal

import pl.barometr.legislative.api.ConsultationDeadline
import pl.barometr.legislative.api.ConsultationId
import pl.barometr.legislative.api.DraftId
import pl.barometr.shared.Ids
import pl.barometr.testing.TestClock
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The calendar as a client actually receives it.
 *
 * Asserted on the emitted text rather than on an object graph, because what is being
 * proven is that Outlook and Google will read it: the half-open end date, the stable
 * identifier a refresh matches on, and the escaping of a Polish title full of commas.
 */
class ConsultationCalendarFeedTest {

    private val clock = TestClock()
    private val feed = ConsultationCalendarFeed(clock)

    @Test
    fun `a deadline is an all-day event ending the day after it closes`() {
        val ics = feed.calendarOf(listOf(deadline(closesOn = LocalDate.of(2026, 3, 30))))

        // Half-open: written any other way, every deadline in the calendar sits a day
        // early or a day late.
        assertContains(ics, "DTSTART;VALUE=DATE:20260330")
        assertContains(ics, "DTEND;VALUE=DATE:20260331")
    }

    @Test
    fun `an event is identified by the consultation, so an extended term replaces it`() {
        val id = ConsultationId(Ids.next())
        val first = feed.calendarOf(listOf(deadline(id = id, closesOn = LocalDate.of(2026, 3, 30))))
        val extended = feed.calendarOf(listOf(deadline(id = id, closesOn = LocalDate.of(2026, 4, 30))))

        assertContains(first, "UID:${id.value}@barometr")
        assertContains(extended, "UID:${id.value}@barometr")
        assertContains(extended, "DTSTART;VALUE=DATE:20260430")
    }

    @Test
    fun `the ministry's own sentence travels with the entry`() {
        val ics = feed.calendarOf(listOf(deadline()))

        assertContains(unfolded(ics), "proszę o zgłoszenie uwag w terminie 21 dni")
        assertContains(unfolded(ics), "Dopasowano przez: keyword = prawo budowlane")
    }

    /**
     * A Polish title carries commas and semicolons, and both end a property value in
     * iCalendar unless escaped. This is the dull half of the format, and the reason it
     * is a library's job rather than a string template's.
     */
    @Test
    fun `a title full of punctuation does not break the format`() {
        val ics = feed.calendarOf(
            listOf(deadline(title = "Projekt ustawy o zmianie ustawy — Prawo budowlane, ustawy o planowaniu; oraz innych")),
        )

        assertContains(unfolded(ics), """SUMMARY:Konsultacje: Projekt ustawy o zmianie ustawy — Prawo budowlane\, ustawy o planowaniu\; oraz innych""")
    }

    /**
     * A three-hundred-character title is folded rather than emitted as one line, which
     * is what an unfolding reader on the other end expects.
     *
     * Counted in characters, not octets. RFC 5545 says seventy-five octets and the
     * library folds at seventy-five characters, so a line of Polish can be a little
     * over — a deviation every calendar client in use tolerates, and the one place this
     * test is deliberately less strict than the specification.
     */
    @Test
    fun `every line is folded to what a reader on the other end expects`() {
        val ics = feed.calendarOf(listOf(deadline(title = "Projekt ".repeat(40))))

        ics.lineSequence().forEach { line ->
            assertTrue(line.length <= 75, "a line longer than the format folds at: $line")
        }
    }

    @Test
    fun `the calendar names itself and says how often to come back`() {
        val ics = feed.calendarOf(listOf(deadline()))

        assertContains(unfolded(ics), "X-WR-CALNAME:Barometr — konsultacje publiczne")
        assertContains(unfolded(ics), "REFRESH-INTERVAL")
        assertContains(unfolded(ics), "X-PUBLISHED-TTL:PT6H")
    }

    @Test
    fun `a profile with nothing open is an empty calendar rather than an error`() {
        val ics = feed.calendarOf(emptyList())

        assertContains(ics, "BEGIN:VCALENDAR")
        assertFalse(ics.contains("BEGIN:VEVENT"))
    }

    @Test
    fun `the entry says how many working days are left, which is what a reader acts on`() {
        val ics = feed.calendarOf(listOf(deadline(workingDaysLeft = 3)))

        assertEquals(1, unfolded(ics).lines().count { it.contains("Dni roboczych do końca: 3.") })
    }

    /** iCalendar continues a long line with CRLF and a space; a reader sees them joined. */
    private fun unfolded(ics: String) = ics.replace("\r\n ", "")

    private fun deadline(
        id: ConsultationId = ConsultationId(Ids.next()),
        title: String = "Projekt ustawy o zmianie ustawy Prawo budowlane",
        closesOn: LocalDate = LocalDate.of(2026, 3, 30),
        workingDaysLeft: Int = 12,
    ) = ProfileDeadline(
        consultation = ConsultationDeadline(
            id = id,
            draftId = DraftId(Ids.next()),
            draftTitle = title,
            opensOn = closesOn.minusDays(21),
            closesOn = closesOn,
            daysAllowed = 21,
            submissionAddress = "konsultacje@ms.gov.pl",
            quote = "proszę o zgłoszenie uwag w terminie 21 dni od dnia otrzymania niniejszego pisma",
        ),
        matchedKind = "keyword",
        matchedValue = "prawo budowlane",
        workingDaysLeft = workingDaysLeft,
    )
}
