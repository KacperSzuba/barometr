package pl.barometr.identity.internal.apikey

import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.ApiScope
import pl.barometr.identity.api.ApiTier
import pl.barometr.identity.internal.jooq.tables.references.API_KEY
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Keys for the public API. SQL only. */
@Repository
@Transactional(readOnly = true)
class ApiKeyRepository(private val dsl: DSLContext) {

    @Transactional
    fun issue(key: IssuedApiKey, hash: String) {
        dsl.insertInto(API_KEY)
            .set(API_KEY.ID, key.id)
            .set(API_KEY.OWNER_ID, key.owner)
            .set(API_KEY.NAME, key.name)
            .set(API_KEY.KEY_HASH, hash)
            .set(API_KEY.TIER, key.tier.wireName)
            .set(API_KEY.SCOPES, key.scopes.map { it.wireName }.sorted().toTypedArray())
            .set(API_KEY.CREATED_AT, at(key.createdAt))
            .set(API_KEY.EXPIRES_AT, key.expiresAt?.let(::at))
            .execute()
    }

    /** The live key this hash names, or null when it names none. */
    fun liveByHash(hash: String, now: Instant): IssuedApiKey? =
        dsl.selectFrom(API_KEY)
            .where(API_KEY.KEY_HASH.eq(hash))
            .and(API_KEY.REVOKED_AT.isNull)
            .and(API_KEY.EXPIRES_AT.isNull.or(API_KEY.EXPIRES_AT.gt(at(now))))
            .fetchOne(::toKey)

    /**
     * Counts one request against a key.
     *
     * Its own transaction, for the reason the rate limiter's is: usage is not part of
     * whatever the request goes on to do, and a failed request is still a request that was
     * made.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordUse(id: UUID, at: Instant) {
        dsl.update(API_KEY)
            .set(API_KEY.REQUESTS, API_KEY.REQUESTS.plus(DSL.inline(1L)))
            .set(API_KEY.LAST_USED_AT, at(at))
            .where(API_KEY.ID.eq(id))
            .execute()
    }

    fun forOwner(owner: UUID): List<IssuedApiKey> =
        dsl.selectFrom(API_KEY)
            .where(API_KEY.OWNER_ID.eq(owner))
            .orderBy(API_KEY.CREATED_AT.desc())
            .fetch(::toKey)

    /** @return false when there was no live key of that owner to revoke. */
    @Transactional
    fun revoke(owner: UUID, id: UUID, at: Instant): Boolean =
        dsl.update(API_KEY)
            .set(API_KEY.REVOKED_AT, at(at))
            .where(API_KEY.ID.eq(id))
            .and(API_KEY.OWNER_ID.eq(owner))
            .and(API_KEY.REVOKED_AT.isNull)
            .execute() > 0

    private fun toKey(record: Record) = IssuedApiKey(
        id = record[API_KEY.ID]!!,
        owner = record[API_KEY.OWNER_ID]!!,
        name = record[API_KEY.NAME]!!,
        // A stored value this enum does not know would mean the `CHECK` and the code
        // drifted apart, which is a state nothing above can interpret.
        tier = ApiTier.of(record[API_KEY.TIER]!!) ?: error("stored api tier"),
        scopes = record[API_KEY.SCOPES]!!.mapNotNull { it?.let(ApiScope::of) }.toSet(),
        createdAt = record[API_KEY.CREATED_AT]!!.toInstant(),
        expiresAt = record[API_KEY.EXPIRES_AT]?.toInstant(),
        revokedAt = record[API_KEY.REVOKED_AT]?.toInstant(),
        lastUsedAt = record[API_KEY.LAST_USED_AT]?.toInstant(),
        requests = record[API_KEY.REQUESTS]!!,
    )

    private fun at(instant: Instant) = instant.atOffset(ZoneOffset.UTC)
}
