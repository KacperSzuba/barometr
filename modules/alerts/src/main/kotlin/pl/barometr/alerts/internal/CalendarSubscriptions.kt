package pl.barometr.alerts.internal

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.ProfileDirectory
import pl.barometr.profiles.api.ProfileId

/**
 * Handing out and taking back the URL a calendar client subscribes to.
 *
 * The ownership check lives here rather than in the endpoint, for the reason every
 * other check in this context does: the rule is "a feed is about a profile you own",
 * and it has to hold for whatever asks next, not only for HTTP.
 */
@Service
class CalendarSubscriptions(
    private val feeds: CalendarFeedRepository,
    private val profiles: ProfileDirectory,
    private val properties: CalendarProperties,
) {

    /** The subscription URL for this profile, minted the first time it is asked for. */
    @Transactional
    fun subscriptionUrl(owner: UserId, profile: ProfileId): String {
        own(owner, profile)
        // A deployment that has not been told where it is reachable cannot hand out a
        // working subscription, and a URL into `localhost` pasted into somebody's
        // calendar is a subscription that never updates and never says why.
        check(properties.baseUrl.isNotBlank()) { "app.alerts.calendar.base-url is not set" }

        return "${properties.baseUrl}$FEED_PATH/${feeds.tokenFor(profile, owner)}.ics"
    }

    /**
     * Stops the current URL working. Subscribing again mints a different one, which is
     * how a link that has been forwarded to the wrong person is taken back.
     */
    @Transactional
    fun revokeSubscription(owner: UserId, profile: ProfileId) {
        own(owner, profile)
        feeds.revoke(profile)
    }

    private fun own(owner: UserId, profile: ProfileId) {
        if (profiles.ownerOf(profile) != owner) throw UnknownProfileException(profile.toString())
    }

    private companion object {
        /** Where [CalendarFeedController] is mapped. One fact, and the URL is built from it. */
        const val FEED_PATH = "/api/v1/alerts/calendar/feed"
    }
}
