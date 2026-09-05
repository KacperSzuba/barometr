package pl.barometr.alerts.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Where a calendar client reaches this API, bound from the `app.alerts.calendar` block.
 *
 * A second base URL beside the one an unsubscribe link uses, and deliberately not the
 * same setting: an unsubscribe link goes to the web application a person clicks in,
 * while a calendar feed is fetched by Outlook or Google server-to-server and must point
 * at this service. On a real deployment they are two hostnames.
 *
 * No production default. A subscription URL handed out pointing at `localhost` is one
 * that silently never updates, which is the failure a deadline calendar cannot have.
 */
@ConfigurationProperties("app.alerts.calendar")
data class CalendarProperties(
    /** Where this API is reachable from a calendar client, without a trailing slash. */
    val baseUrl: String = "",
    /**
     * How far ahead the feed looks, in days. Ninety: a quarter is what a calendar shows
     * and further than any consultation period Polish practice uses, so nothing real is
     * cut off and the feed stays a few dozen events rather than a year of history.
     */
    val horizonDays: Long = 90,
) {
    init {
        require(horizonDays in 1..365) { "The feed looks between a day and a year ahead, got $horizonDays" }
    }
}
