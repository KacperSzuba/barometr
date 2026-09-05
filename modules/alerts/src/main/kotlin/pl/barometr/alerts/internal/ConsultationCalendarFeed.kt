package pl.barometr.alerts.internal

import biweekly.ICalVersion
import biweekly.ICalendar
import biweekly.component.StandardTime
import biweekly.component.VEvent
import biweekly.component.VTimezone
import biweekly.io.TimezoneAssignment
import biweekly.io.text.ICalWriter
import biweekly.property.Status
import biweekly.util.DateTimeComponents
import biweekly.util.Duration
import biweekly.util.UtcOffset
import org.springframework.stereotype.Service
import java.io.StringWriter
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.TimeZone

/**
 * A profile's open consultations, as a calendar somebody's client can subscribe to.
 *
 * The feature that turns this product from a newsletter into a thing a working week is
 * organised around: the deadlines appear beside the meetings, and they update
 * themselves.
 *
 * **All-day events, and the end date is the day after.** An iCalendar all-day range is
 * half-open, so a consultation closing on the 30th is `DTSTART;VALUE=DATE:20260330`
 * and `DTEND;VALUE=DATE:20260331`. Written the other way, every deadline in the
 * calendar sits a day early — the sort of dull mistake that is worth a library and a
 * test rather than a comment saying to be careful.
 *
 * **The identifier is the consultation's.** A client matches events across refreshes by
 * `UID`, so a consultation a ministry extends has to keep the one it was published
 * with; a new identifier would leave the old date sitting in the calendar beside the
 * new one, and the reader would trust whichever they saw first.
 *
 * **Folding is the library's, at seventy-five characters.** The specification counts
 * octets, so a line of Polish can run a few bytes over. Every calendar client in use
 * unfolds it regardless, and the alternative — folding by hand, in UTF-8, inside
 * escaped text — is exactly the kind of code that is wrong for a year before anybody
 * notices.
 *
 * **No alarms.** The product already warns by mail at fourteen, seven, three and one
 * working days out. A calendar alarm on top of that is the same news twice, from two
 * systems the reader would then have to reconcile.
 */
@Service
class ConsultationCalendarFeed(private val clock: Clock) {

    fun calendarOf(deadlines: List<ProfileDeadline>): String {
        val calendar = ICalendar().apply {
            setProductId(PRODUCT_ID)
            // What the client shows in its sidebar, and how often it is worth asking
            // again. Six hours matches how often the deadline watch looks at the
            // calendar: polling more often would only ever see the same answer.
            //
            // Twice each, and deliberately. `NAME` and `REFRESH-INTERVAL` are the
            // standard properties (RFC 7986, 2016); `X-WR-CALNAME` and
            // `X-PUBLISHED-TTL` are what Google and Outlook actually read, and have
            // for twenty years. Sending only the standard ones means an unnamed
            // calendar polled on somebody else's schedule.
            setName(CALENDAR_NAME)
            setRefreshInterval(Duration.builder().hours(REFRESH_HOURS).build())
            addExperimentalProperty("X-WR-CALNAME", CALENDAR_NAME)
            addExperimentalProperty("X-PUBLISHED-TTL", "PT${REFRESH_HOURS}H")
        }

        deadlines.forEach { calendar.addEvent(eventFor(it)) }

        return written(calendar)
    }

    /**
     * UTC as the writer's timezone, matching how the dates below are built: a floating
     * date formatted in one zone from a `Date` built in another is the classic way an
     * all-day event lands on the wrong day.
     *
     * Written through the writer rather than through `Biweekly.write(…).tz(zone, …)`,
     * and that is not a preference. The convenience method has one implementation of
     * "which timezone": download the definition from `tzurl.org`, per call, over plain
     * HTTP. A calendar feed that cannot be produced when a third-party site is blocked,
     * slow or gone is not a feed anybody can subscribe to — and this deployment's egress
     * refuses it outright, which is how it was found. The definition of UTC is four
     * lines and does not change, so it is stated here.
     */
    private fun written(calendar: ICalendar): String {
        val text = StringWriter()

        ICalWriter(text, ICalVersion.V2_0).use { writer ->
            writer.globalTimezone = UTC
            writer.write(calendar)
        }

        return text.toString()
    }

    private fun eventFor(deadline: ProfileDeadline): VEvent = VEvent().apply {
        val consultation = deadline.consultation

        setUid("${consultation.id.value}@barometr")
        setDateStart(midnight(consultation.closesOn), false)
        setDateEnd(midnight(consultation.closesOn.plusDays(1)), false)
        setSummary("Konsultacje: ${consultation.draftTitle}")
        setDescription(describe(deadline))
        setStatus(Status.confirmed())
        setDateTimeStamp(Date.from(clock.instant()))
    }

    /**
     * What the entry says when it is opened: the ministry's own sentence, how much time
     * is left in working days, where comments go, and which interest caught it.
     *
     * The quote is there for the reason it is carried everywhere else — a reader who
     * doubts the date can check it against the letter rather than write in to ask.
     */
    private fun describe(deadline: ProfileDeadline): String = buildString {
        appendLine("Termin zgłaszania uwag: ${deadline.consultation.closesOn}.")
        appendLine("Dni roboczych do końca: ${deadline.workingDaysLeft}.")
        deadline.consultation.submissionAddress?.let { appendLine("Uwagi: $it") }
        appendLine()
        appendLine("Z pisma resortu: „${deadline.consultation.quote}”")
        appendLine()
        append("Dopasowano przez: ${deadline.matchedKind} = ${deadline.matchedValue}.")
    }

    /**
     * The start of the day in UTC, which is what the writer above formats back as a
     * bare date.
     */
    private fun midnight(day: LocalDate): Date = Date.from(day.atStartOfDay(ZoneOffset.UTC).toInstant())

    private companion object {
        /** UTC, stated rather than fetched. `STANDARD` with no offset either side is all it is. */
        val UTC: TimezoneAssignment = TimezoneAssignment(
            TimeZone.getTimeZone(ZoneOffset.UTC),
            VTimezone("UTC").apply {
                addStandardTime(
                    StandardTime().apply {
                        setDateStart(DateTimeComponents.parse("19700101T000000"))
                        setTimezoneOffsetFrom(UtcOffset(0L))
                        setTimezoneOffsetTo(UtcOffset(0L))
                    },
                )
            },
        )

        const val PRODUCT_ID = "-//Barometr//Konsultacje publiczne//PL"
        const val CALENDAR_NAME = "Barometr — konsultacje publiczne"

        /** Six hours, the cadence at which the deadline watch itself looks at the calendar. */
        const val REFRESH_HOURS = 6
    }
}
