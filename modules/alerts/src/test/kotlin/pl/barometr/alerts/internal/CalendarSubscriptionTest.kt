package pl.barometr.alerts.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.alerts.internal.jooq.tables.references.CALENDAR_FEED
import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.ProfileDirectory
import pl.barometr.profiles.api.ProfileId
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The capability behind a subscribed calendar: who may have one, that it stays the
 * same, and that revoking it works.
 */
class CalendarSubscriptionTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private lateinit var feeds: CalendarFeedRepository
    private lateinit var subscriptions: CalendarSubscriptions

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(CALENDAR_FEED).execute()
        feeds = CalendarFeedRepository(dsl, clock)
        subscriptions = CalendarSubscriptions(feeds, OwnedProfiles, CalendarProperties(baseUrl = BASE_URL))
    }

    /**
     * The URL has been pasted into somebody's calendar client and is fetched from there
     * for years. A token that changed under it would leave a subscription that keeps
     * working and stops updating.
     */
    @Test
    fun `asking twice returns the same subscription`() {
        val first = subscriptions.subscriptionUrl(OWNER, PROFILE)
        val second = subscriptions.subscriptionUrl(OWNER, PROFILE)

        assertEquals(first, second)
        assertTrue(first.startsWith("$BASE_URL/api/v1/alerts/calendar/feed/"), first)
        assertTrue(first.endsWith(".ics"), "clients decide what a URL is by looking at it: $first")
    }

    @Test
    fun `the token names the profile it was minted for`() {
        val token = tokenIn(subscriptions.subscriptionUrl(OWNER, PROFILE))

        assertEquals(SubscribedFeed(PROFILE, OWNER), feeds.feedFor(token))
    }

    @Test
    fun `a revoked subscription names nothing, and subscribing again mints another`() {
        val token = tokenIn(subscriptions.subscriptionUrl(OWNER, PROFILE))

        subscriptions.revokeSubscription(OWNER, PROFILE)

        assertNull(feeds.feedFor(token), "a revoked token is a token that stops working")
        assertNotEquals(token, tokenIn(subscriptions.subscriptionUrl(OWNER, PROFILE)))
    }

    @Test
    fun `a feed can only be had for a profile the caller owns`() {
        assertFailsWith<UnknownProfileException> {
            subscriptions.subscriptionUrl(UserId(Ids.next()), PROFILE)
        }
        assertFailsWith<UnknownProfileException> {
            subscriptions.revokeSubscription(UserId(Ids.next()), PROFILE)
        }
    }

    @Test
    fun `a token nobody minted names nothing`() {
        assertNull(feeds.feedFor("nie-ma-takiego-tokenu"))
    }

    private fun tokenIn(url: String) = url.substringAfterLast('/').removeSuffix(".ics")

    /** The profile directory, as much of it as an ownership check needs. */
    private object OwnedProfiles : ProfileDirectory {
        override fun ownerOf(profile: ProfileId): UserId? = OWNER.takeIf { profile == PROFILE }
    }

    private companion object {
        const val BASE_URL = "https://api.barometr.example"

        val OWNER = UserId(Ids.next())
        val PROFILE = ProfileId(Ids.next())
    }
}
