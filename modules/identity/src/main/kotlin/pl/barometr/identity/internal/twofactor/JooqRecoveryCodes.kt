package pl.barometr.identity.internal.twofactor

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.internal.jooq.tables.references.RECOVERY_CODE
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** [RecoveryCodes] over jOOQ. */
@Repository
@Transactional(readOnly = true)
class JooqRecoveryCodes(private val dsl: DSLContext) : RecoveryCodes {

    @Transactional
    override fun replaceAll(userId: UUID, hashes: List<String>, at: Instant) {
        dsl.deleteFrom(RECOVERY_CODE).where(RECOVERY_CODE.USER_ID.eq(userId)).execute()

        dsl.batch(
            hashes.map { hash ->
                dsl.insertInto(RECOVERY_CODE)
                    .set(RECOVERY_CODE.USER_ID, userId)
                    .set(RECOVERY_CODE.CODE_HASH, hash)
                    .set(RECOVERY_CODE.CREATED_AT, at.atOffset(ZoneOffset.UTC))
            },
        ).execute()
    }

    /**
     * `used_at IS NULL` is the claim rather than a check: two requests presenting the
     * same code at once means one of them updates a row and the other updates nothing.
     */
    @Transactional
    override fun consume(userId: UUID, hash: String, at: Instant): Boolean =
        dsl.update(RECOVERY_CODE)
            .set(RECOVERY_CODE.USED_AT, at.atOffset(ZoneOffset.UTC))
            .where(RECOVERY_CODE.USER_ID.eq(userId))
            .and(RECOVERY_CODE.CODE_HASH.eq(hash))
            .and(RECOVERY_CODE.USED_AT.isNull)
            .execute() > 0

    override fun unusedCount(userId: UUID): Int =
        dsl.fetchCount(
            RECOVERY_CODE,
            RECOVERY_CODE.USER_ID.eq(userId).and(RECOVERY_CODE.USED_AT.isNull),
        )

    @Transactional
    override fun deleteAll(userId: UUID) {
        dsl.deleteFrom(RECOVERY_CODE).where(RECOVERY_CODE.USER_ID.eq(userId)).execute()
    }
}
