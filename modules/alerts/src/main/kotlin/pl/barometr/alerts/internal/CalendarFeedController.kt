package pl.barometr.alerts.internal

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The calendar itself, fetched by whatever the subscriber pasted the URL into.
 *
 * Unauthenticated, and it has to be: a calendar client subscribes once and then fetches
 * for years from a server-side scheduler, with nowhere to put a bearer token and nobody
 * at the keyboard to be prompted. The token in the URL is the whole authorisation — it
 * is random, it is revocable, and all it can reach is one profile's view of deadlines
 * that are public to begin with.
 *
 * The `.ics` suffix is not decoration. Several clients — Outlook among them — decide
 * whether a URL is a calendar by looking at it before they fetch anything.
 */
@RestController
@RequestMapping("/api/v1/alerts/calendar/feed")
class CalendarFeedController(
    private val feeds: CalendarFeedRepository,
    private val deadlines: ProfileDeadlines,
    private val calendar: ConsultationCalendarFeed,
) {

    @GetMapping("/{token}.ics", produces = [CALENDAR_MEDIA_TYPE])
    fun feed(@PathVariable token: String): String {
        val subscribed = feeds.feedFor(token) ?: throw UnknownCalendarFeedException()

        return calendar.calendarOf(deadlines.openFor(subscribed.profile, subscribed.owner))
    }

    private companion object {
        /**
         * The type RFC 5545 gives the format. A client that is handed `application/json`
         * here refuses the body without looking at it.
         */
        const val CALENDAR_MEDIA_TYPE = "text/calendar;charset=UTF-8"
    }
}
