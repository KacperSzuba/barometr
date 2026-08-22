package pl.barometr.alerts.internal

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/**
 * What the mail provider tells us happened after we handed a message over.
 *
 * A hard bounce means the address does not exist, and a complaint means somebody
 * pressed "spam". Both stop the mail at once, and for the same reason: continuing to
 * send costs the sending domain its reputation, which every other alert this product
 * sends then rides on.
 *
 * **The shape is ours, not a provider's.** Every provider posts its own JSON, so
 * connecting one means a small adapter between their shape and this — twenty lines,
 * written against a recorded payload the day there is an account to record one from.
 * Guessing at somebody's JSON from documentation and calling it a contract test is how
 * a webhook comes to look tested and parse nothing.
 *
 * Not part of the signed-in API: the caller is a machine with no account, holding a
 * shared secret. Blank secret means the endpoint refuses everything, because an
 * endpoint that suppresses addresses on anybody's say-so is an endpoint for silencing
 * other people's alerts.
 */
@RestController
@RequestMapping("/api/v1/alerts/email-events")
class EmailEventController(
    private val suppressions: SuppressionRepository,
    private val properties: EmailProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun report(
        @RequestHeader(name = SECRET_HEADER, required = false) secret: String?,
        @Valid @RequestBody event: EmailEvent,
    ) {
        if (!authentic(secret)) throw UnauthorizedReportException()

        val reason = SuppressionReason.of(event.event.trim().lowercase())
            ?.takeIf { it != SuppressionReason.UNSUBSCRIBED }
            ?: throw UnknownEmailEventException(event.event)

        suppressions.suppress(event.address, reason, event.detail)
        log.info("Suppressed an address after a {} report", reason.wireName)
    }

    /**
     * Compared in constant time. A secret checked with `==` leaks its prefix to
     * anybody willing to time a few thousand requests, and this one is a licence to
     * stop somebody's alerts.
     */
    private fun authentic(presented: String?): Boolean {
        if (properties.webhookSecret.isBlank() || presented == null) return false

        return MessageDigest.isEqual(
            presented.toByteArray(StandardCharsets.UTF_8),
            properties.webhookSecret.toByteArray(StandardCharsets.UTF_8),
        )
    }

    /**
     * What a provider's adapter reports. `unsubscribed` is deliberately not accepted
     * here — that one comes from a person pressing a link, and accepting it from a
     * machine would let one report unsubscribe somebody else.
     */
    data class EmailEvent(
        @field:NotBlank
        @field:Email
        val address: String,
        @field:NotBlank
        val event: String,
        /** What the provider actually said, kept verbatim on the suppression. */
        val detail: String? = null,
    )

    private companion object {
        const val SECRET_HEADER = "X-Barometr-Webhook-Secret"
    }
}
