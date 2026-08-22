package pl.barometr.alerts.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import pl.barometr.alerts.internal.jooq.tables.references.UNSUBSCRIBE_TOKEN
import pl.barometr.identity.api.UserId
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

/**
 * The one-click way out. SQL only, plus the randomness that makes it a capability.
 */
@Repository
class UnsubscribeTokenRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * This person's token, minted on first use and stable afterwards.
     *
     * Stable because it goes in every message: a token that changed per digest would
     * make an old mail's unsubscribe link dead, and somebody who cannot unsubscribe
     * from the mail in front of them presses the spam button instead.
     */
    fun tokenFor(owner: UserId): String {
        val minted = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(TOKEN_BYTES).also(RANDOM::nextBytes),
        )

        return dsl.insertInto(UNSUBSCRIBE_TOKEN)
            .set(UNSUBSCRIBE_TOKEN.TOKEN, minted)
            .set(UNSUBSCRIBE_TOKEN.OWNER_ID, owner.value)
            .set(UNSUBSCRIBE_TOKEN.CREATED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .onConflict(UNSUBSCRIBE_TOKEN.OWNER_ID)
            // Nothing to change, but the clause is what makes the existing token come
            // back from `returning` instead of nothing at all.
            .doUpdate()
            .set(UNSUBSCRIBE_TOKEN.OWNER_ID, owner.value)
            .returning(UNSUBSCRIBE_TOKEN.TOKEN)
            .fetchOne()!!
            .token!!
    }

    fun ownerOf(token: String): UserId? =
        dsl.select(UNSUBSCRIBE_TOKEN.OWNER_ID)
            .from(UNSUBSCRIBE_TOKEN)
            .where(UNSUBSCRIBE_TOKEN.TOKEN.eq(token))
            .fetchOne()
            ?.value1()
            ?.let(::UserId)

    private companion object {
        /**
         * 256 bits. The token is the whole authorisation — whoever holds it can stop
         * somebody's mail — so it has to be past guessing, and it costs 43 characters
         * in a URL.
         */
        const val TOKEN_BYTES = 32

        val RANDOM = SecureRandom()
    }
}
