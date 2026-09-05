package pl.barometr.identity.internal.twofactor

import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.internal.jooq.tables.references.LOGIN_CHALLENGE
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** [LoginChallenges] over jOOQ. */
@Repository
@Transactional(readOnly = true)
class JooqLoginChallenges(private val dsl: DSLContext) : LoginChallenges {

    @Transactional
    override fun open(challenge: LoginChallenge): LoginChallenge {
        dsl.insertInto(LOGIN_CHALLENGE)
            .set(LOGIN_CHALLENGE.ID, challenge.id)
            .set(LOGIN_CHALLENGE.USER_ID, challenge.userId)
            .set(LOGIN_CHALLENGE.EXPIRES_AT, challenge.expiresAt.atOffset(ZoneOffset.UTC))
            .set(LOGIN_CHALLENGE.ATTEMPTS, challenge.attempts)
            .set(LOGIN_CHALLENGE.CREATED_AT, challenge.createdAt.atOffset(ZoneOffset.UTC))
            .execute()

        return challenge
    }

    /**
     * `SELECT … FOR UPDATE`: the attempt counter and the single use of a challenge are
     * both decided under this lock, so two answers arriving together are serialised
     * rather than racing — the same mechanism refresh rotation relies on.
     */
    override fun byIdForUpdate(id: UUID): LoginChallenge? =
        dsl.selectFrom(LOGIN_CHALLENGE)
            .where(LOGIN_CHALLENGE.ID.eq(id))
            .forUpdate()
            .fetchOne(::toChallenge)

    @Transactional
    override fun recordAttempt(id: UUID): Int =
        dsl.update(LOGIN_CHALLENGE)
            .set(LOGIN_CHALLENGE.ATTEMPTS, LOGIN_CHALLENGE.ATTEMPTS.plus(1))
            .where(LOGIN_CHALLENGE.ID.eq(id))
            .returningResult(LOGIN_CHALLENGE.ATTEMPTS)
            .fetchOne()
            ?.value1()
            ?: 0

    @Transactional
    override fun consume(id: UUID, at: Instant): Boolean =
        dsl.update(LOGIN_CHALLENGE)
            .set(LOGIN_CHALLENGE.CONSUMED_AT, at.atOffset(ZoneOffset.UTC))
            .where(LOGIN_CHALLENGE.ID.eq(id))
            .and(LOGIN_CHALLENGE.CONSUMED_AT.isNull)
            .execute() > 0

    @Transactional
    override fun deleteFinishedBefore(cutoff: Instant): Int =
        dsl.deleteFrom(LOGIN_CHALLENGE)
            .where(LOGIN_CHALLENGE.EXPIRES_AT.lt(cutoff.atOffset(ZoneOffset.UTC)))
            .execute()

    private fun toChallenge(record: Record) = LoginChallenge(
        id = record[LOGIN_CHALLENGE.ID]!!,
        userId = record[LOGIN_CHALLENGE.USER_ID]!!,
        expiresAt = record[LOGIN_CHALLENGE.EXPIRES_AT]!!.toInstant(),
        consumedAt = record[LOGIN_CHALLENGE.CONSUMED_AT]?.toInstant(),
        attempts = record[LOGIN_CHALLENGE.ATTEMPTS]!!,
        createdAt = record[LOGIN_CHALLENGE.CREATED_AT]!!.toInstant(),
    )
}
