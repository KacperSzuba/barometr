package pl.barometr.alerts.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * How long alerts keeps what it has told somebody, bound from the `app.alerts.retention`
 * block.
 *
 * Two categories, two answers, and the difference is worth stating. A notification is
 * something a person may want to look back at — "when was I told about this bill" — so it
 * is kept for two years. A decision is the engine's own record of why somebody was *not*
 * told, which answers support's first question and stops being interesting long before
 * the notification does; a year of it is more than anybody has ever asked for.
 */
@ConfigurationProperties("app.alerts.retention")
data class AlertRetentionProperties(
    val notifications: Duration = Duration.ofDays(730),
    val decisions: Duration = Duration.ofDays(365),
    /** Deliveries are a mail-server log, kept as long as the digests they describe. */
    val deliveries: Duration = Duration.ofDays(365),
) {
    init {
        require(!notifications.isNegative && !notifications.isZero) { "Notifications are kept for a positive time" }
        require(!decisions.isNegative && !decisions.isZero) { "Decisions are kept for a positive time" }
        require(!deliveries.isNegative && !deliveries.isZero) { "Deliveries are kept for a positive time" }
    }
}
