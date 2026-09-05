package pl.barometr.alerts.internal

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.barometr.identity.api.callerOf
import pl.barometr.profiles.api.ProfileId
import java.security.Principal
import java.util.UUID

/**
 * Getting the subscription URL for a profile, and taking it back.
 *
 * Signed in, unlike the feed itself: this is where the capability is handed out, so it
 * is the one place that has to know who is asking.
 */
@RestController
@RequestMapping("/api/v1/alerts/calendar/subscriptions")
class CalendarSubscriptionController(private val subscriptions: CalendarSubscriptions) {

    /**
     * Idempotent on purpose: asking twice returns the same URL rather than replacing a
     * subscription somebody has already pasted into their calendar.
     */
    @PostMapping("/{profileId}")
    fun subscribe(caller: Principal, @PathVariable profileId: UUID): SubscriptionResponse =
        SubscriptionResponse(subscriptions.subscriptionUrl(callerOf(caller), ProfileId(profileId)))

    @DeleteMapping("/{profileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(caller: Principal, @PathVariable profileId: UUID) {
        subscriptions.revokeSubscription(callerOf(caller), ProfileId(profileId))
    }

    data class SubscriptionResponse(
        /** Paste into Outlook, Google Calendar or Apple Calendar as a subscribed calendar. */
        val url: String,
    )
}
