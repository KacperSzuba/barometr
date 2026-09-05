package pl.barometr.identity.internal.twofactor

import org.jooq.DSLContext
import org.springframework.security.crypto.encrypt.TextEncryptor
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.internal.jooq.tables.references.TOTP_SECRET
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [TwoFactorSecrets] over jOOQ, encrypting on the way in and decrypting on the way out.
 *
 * The encryption sits here rather than in the service above for the same reason hashing
 * a refresh token does: it is a fact about how the row is written, and everything above
 * this line is better off dealing in the secret itself.
 */
@Repository
@Transactional(readOnly = true)
class JooqTwoFactorSecrets(
    private val dsl: DSLContext,
    private val encryptor: TextEncryptor,
) : TwoFactorSecrets {

    /**
     * Setting up again replaces whatever was there, confirmation included.
     *
     * That is the intent: somebody who has lost their phone starts over, and leaving the
     * old secret confirmed would leave a factor nobody can produce standing in the way.
     */
    @Transactional
    override fun save(secret: EnrolledSecret) {
        dsl.insertInto(TOTP_SECRET)
            .set(TOTP_SECRET.USER_ID, secret.userId)
            .set(TOTP_SECRET.SECRET, encryptor.encrypt(secret.secret))
            .set(TOTP_SECRET.CONFIRMED_AT, secret.confirmedAt?.atOffset(ZoneOffset.UTC))
            .set(TOTP_SECRET.CREATED_AT, secret.createdAt.atOffset(ZoneOffset.UTC))
            .onConflict(TOTP_SECRET.USER_ID)
            .doUpdate()
            .set(TOTP_SECRET.SECRET, encryptor.encrypt(secret.secret))
            .set(TOTP_SECRET.CONFIRMED_AT, secret.confirmedAt?.atOffset(ZoneOffset.UTC))
            .set(TOTP_SECRET.CREATED_AT, secret.createdAt.atOffset(ZoneOffset.UTC))
            .execute()
    }

    override fun forUser(userId: UUID): EnrolledSecret? =
        dsl.selectFrom(TOTP_SECRET)
            .where(TOTP_SECRET.USER_ID.eq(userId))
            .fetchOne()
            ?.let { record ->
                EnrolledSecret(
                    userId = record.userId!!,
                    secret = encryptor.decrypt(record.secret!!),
                    confirmedAt = record.confirmedAt?.toInstant(),
                    createdAt = record.createdAt!!.toInstant(),
                )
            }

    @Transactional
    override fun confirm(userId: UUID, at: Instant): Boolean =
        dsl.update(TOTP_SECRET)
            .set(TOTP_SECRET.CONFIRMED_AT, at.atOffset(ZoneOffset.UTC))
            .where(TOTP_SECRET.USER_ID.eq(userId))
            .and(TOTP_SECRET.CONFIRMED_AT.isNull)
            .execute() > 0

    @Transactional
    override fun delete(userId: UUID): Boolean =
        dsl.deleteFrom(TOTP_SECRET).where(TOTP_SECRET.USER_ID.eq(userId)).execute() > 0
}
