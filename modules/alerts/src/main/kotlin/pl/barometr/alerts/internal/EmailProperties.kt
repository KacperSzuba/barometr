package pl.barometr.alerts.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * What this system needs to know about the mailbox it sends from.
 *
 * The server itself is `spring.mail.*`, which Boot already owns; these are the two
 * things it does not: who the mail says it is from, and where an unsubscribe link
 * points.
 *
 * Nothing here has a production default. A digest sent from the wrong address, or
 * carrying an unsubscribe link into `localhost`, is worse than one not sent at all —
 * the first teaches a mail provider to distrust the domain, and the second teaches a
 * person to press the spam button.
 */
@ConfigurationProperties("app.alerts.email")
data class EmailProperties(
    /** The `From:` address. Its domain is the one SPF, DKIM and DMARC are set up for. */
    val from: String = "",
    /** Where an unsubscribe link points, without a trailing slash. */
    val unsubscribeBaseUrl: String = "",
    /**
     * What a provider must present to report a bounce.
     *
     * Blank means the webhook refuses everything, which is the right way round: an
     * endpoint that suppresses addresses on anybody's say-so is an endpoint for
     * silencing other people's alerts.
     */
    val webhookSecret: String = "",
)
