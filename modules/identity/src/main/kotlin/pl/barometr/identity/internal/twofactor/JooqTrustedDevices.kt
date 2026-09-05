package pl.barometr.identity.internal.twofactor

import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.internal.jooq.tables.references.TRUSTED_DEVICE
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** [TrustedDevices] over jOOQ. */
@Repository
@Transactional(readOnly = true)
class JooqTrustedDevices(private val dsl: DSLContext) : TrustedDevices {

    @Transactional
    override fun remember(device: RememberedDevice): RememberedDevice {
        dsl.insertInto(TRUSTED_DEVICE)
            .set(TRUSTED_DEVICE.ID, device.id)
            .set(TRUSTED_DEVICE.USER_ID, device.userId)
            .set(TRUSTED_DEVICE.TOKEN_HASH, device.tokenHash)
            .set(TRUSTED_DEVICE.USER_AGENT, device.userAgent)
            .set(TRUSTED_DEVICE.CREATED_AT, at(device.createdAt))
            .set(TRUSTED_DEVICE.EXPIRES_AT, at(device.expiresAt))
            .execute()

        return device
    }

    /**
     * Expiry is part of the query rather than a check afterwards: a token that has run
     * out must not be found at all, so that no path above this can forget to look.
     */
    override fun byTokenHash(hash: String, now: Instant): RememberedDevice? =
        dsl.selectFrom(TRUSTED_DEVICE)
            .where(TRUSTED_DEVICE.TOKEN_HASH.eq(hash))
            .and(TRUSTED_DEVICE.REVOKED_AT.isNull)
            .and(TRUSTED_DEVICE.EXPIRES_AT.gt(at(now)))
            .fetchOne(::toDevice)

    @Transactional
    override fun markUsed(id: UUID, at: Instant) {
        dsl.update(TRUSTED_DEVICE)
            .set(TRUSTED_DEVICE.LAST_USED_AT, at(at))
            .where(TRUSTED_DEVICE.ID.eq(id))
            .execute()
    }

    override fun liveFor(userId: UUID, now: Instant): List<RememberedDevice> =
        dsl.selectFrom(TRUSTED_DEVICE)
            .where(TRUSTED_DEVICE.USER_ID.eq(userId))
            .and(TRUSTED_DEVICE.REVOKED_AT.isNull)
            .and(TRUSTED_DEVICE.EXPIRES_AT.gt(at(now)))
            .orderBy(TRUSTED_DEVICE.CREATED_AT.desc())
            .fetch(::toDevice)

    @Transactional
    override fun revoke(userId: UUID, id: UUID, at: Instant): Boolean =
        dsl.update(TRUSTED_DEVICE)
            .set(TRUSTED_DEVICE.REVOKED_AT, at(at))
            .where(TRUSTED_DEVICE.ID.eq(id))
            .and(TRUSTED_DEVICE.USER_ID.eq(userId))
            .and(TRUSTED_DEVICE.REVOKED_AT.isNull)
            .execute() > 0

    @Transactional
    override fun revokeAll(userId: UUID, at: Instant): Int =
        dsl.update(TRUSTED_DEVICE)
            .set(TRUSTED_DEVICE.REVOKED_AT, at(at))
            .where(TRUSTED_DEVICE.USER_ID.eq(userId))
            .and(TRUSTED_DEVICE.REVOKED_AT.isNull)
            .execute()

    private fun toDevice(record: Record) = RememberedDevice(
        id = record[TRUSTED_DEVICE.ID]!!,
        userId = record[TRUSTED_DEVICE.USER_ID]!!,
        tokenHash = record[TRUSTED_DEVICE.TOKEN_HASH]!!,
        userAgent = record[TRUSTED_DEVICE.USER_AGENT],
        createdAt = record[TRUSTED_DEVICE.CREATED_AT]!!.toInstant(),
        expiresAt = record[TRUSTED_DEVICE.EXPIRES_AT]!!.toInstant(),
        lastUsedAt = record[TRUSTED_DEVICE.LAST_USED_AT]?.toInstant(),
        revokedAt = record[TRUSTED_DEVICE.REVOKED_AT]?.toInstant(),
    )

    private fun at(instant: Instant) = instant.atOffset(ZoneOffset.UTC)
}
