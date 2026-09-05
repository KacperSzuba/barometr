package pl.barometr.alerts.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import pl.barometr.alerts.internal.jooq.tables.references.CALENDAR_FEED
import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.ProfileId
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

/**
 * The token a calendar client subscribes with. SQL only, plus the randomness that makes
 * the token a capability.
 */
@Repository
class CalendarFeedRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * This profile's feed token, minted on first use and stable afterwards.
     *
     * Stable for the reason the unsubscribe token is: the URL has been pasted into
     * somebody's calendar client and is fetched from there for years. A token that
     * changed under it would leave a subscription that keeps working and stops
     * updating, which is worse than one that visibly breaks.
     */
    fun tokenFor(profile: ProfileId, owner: UserId): String {
        val minted = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(TOKEN_BYTES).also(RANDOM::nextBytes),
        )

        return dsl.insertInto(CALENDAR_FEED)
            .set(CALENDAR_FEED.PROFILE_ID, profile.value)
            .set(CALENDAR_FEED.OWNER_ID, owner.value)
            .set(CALENDAR_FEED.TOKEN, minted)
            .set(CALENDAR_FEED.CREATED_AT, now())
            .onConflict(CALENDAR_FEED.PROFILE_ID)
            // Nothing to change, but the clause is what makes the existing token come
            // back from `returning` instead of nothing at all.
            .doUpdate()
            .set(CALENDAR_FEED.OWNER_ID, owner.value)
            .returning(CALENDAR_FEED.TOKEN)
            .fetchOne()!!
            .token!!
    }

    /** Whose feed this token is, or null when it names nothing. */
    fun feedFor(token: String): SubscribedFeed? =
        dsl.select(CALENDAR_FEED.PROFILE_ID, CALENDAR_FEED.OWNER_ID)
            .from(CALENDAR_FEED)
            .where(CALENDAR_FEED.TOKEN.eq(token))
            .fetchOne()
            ?.let { SubscribedFeed(ProfileId(it.value1()!!), UserId(it.value2()!!)) }

    /** @return false when there was no feed to revoke, which is not an error. */
    fun revoke(profile: ProfileId): Boolean =
        dsl.deleteFrom(CALENDAR_FEED).where(CALENDAR_FEED.PROFILE_ID.eq(profile.value)).execute() > 0

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private companion object {
        /**
         * 256 bits. The token is the whole authorisation — whoever holds it sees what a
         * profile is watching — so it has to be past guessing, and it costs 43
         * characters in a URL nobody types by hand.
         */
        const val TOKEN_BYTES = 32

        val RANDOM = SecureRandom()
    }
}
