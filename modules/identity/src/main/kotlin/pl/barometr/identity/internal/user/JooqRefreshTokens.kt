package pl.barometr.identity.internal.user

import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.internal.jooq.tables.references.REFRESH_TOKENS
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** [RefreshTokens] over jOOQ. */
@Repository
class JooqRefreshTokens(private val dsl: DSLContext) : RefreshTokens {

    /**
     * `SELECT … FOR UPDATE`: the row is held for the rest of the transaction, so two
     * refreshes presenting the same token are serialised here rather than racing.
     * This is the whole mechanism behind the grace window, and it is why the window
     * works across instances.
     */
    override fun byTokenHashForUpdate(hash: String): RefreshToken? =
        dsl.selectFrom(REFRESH_TOKENS)
            .where(REFRESH_TOKENS.TOKEN_HASH.eq(hash))
            .forUpdate()
            .fetchOne(::toRefreshToken)

    @Transactional
    override fun add(token: RefreshToken): RefreshToken {
        dsl.insertInto(REFRESH_TOKENS)
            .set(REFRESH_TOKENS.ID, token.id)
            .set(REFRESH_TOKENS.USER_ID, token.userId)
            .set(REFRESH_TOKENS.TOKEN_HASH, token.tokenHash)
            .set(REFRESH_TOKENS.FAMILY_ID, token.familyId)
            .set(REFRESH_TOKENS.PREDECESSOR_ID, token.predecessorId)
            .set(REFRESH_TOKENS.EXPIRES_AT, token.expiresAt.atOffset(ZoneOffset.UTC))
            .set(REFRESH_TOKENS.CREATED_AT, token.createdAt.atOffset(ZoneOffset.UTC))
            .execute()
        return token
    }

    /** `used_at IS NULL` in the predicate: only the first use opens the window. */
    @Transactional
    override fun markUsed(id: UUID, at: Instant) {
        dsl.update(REFRESH_TOKENS)
            .set(REFRESH_TOKENS.USED_AT, at.atOffset(ZoneOffset.UTC))
            .where(REFRESH_TOKENS.ID.eq(id))
            .and(REFRESH_TOKENS.USED_AT.isNull)
            .execute()
    }

    @Transactional
    override fun revokeFamily(familyId: UUID, at: Instant): Int =
        dsl.update(REFRESH_TOKENS)
            .set(REFRESH_TOKENS.REVOKED_AT, at.atOffset(ZoneOffset.UTC))
            .where(REFRESH_TOKENS.FAMILY_ID.eq(familyId))
            .and(REFRESH_TOKENS.REVOKED_AT.isNull)
            .execute()

    private fun toRefreshToken(record: Record) = RefreshToken(
        id = record[REFRESH_TOKENS.ID]!!,
        userId = record[REFRESH_TOKENS.USER_ID]!!,
        tokenHash = record[REFRESH_TOKENS.TOKEN_HASH]!!,
        familyId = record[REFRESH_TOKENS.FAMILY_ID]!!,
        predecessorId = record[REFRESH_TOKENS.PREDECESSOR_ID],
        expiresAt = record[REFRESH_TOKENS.EXPIRES_AT]!!.toInstant(),
        usedAt = record[REFRESH_TOKENS.USED_AT]?.toInstant(),
        revokedAt = record[REFRESH_TOKENS.REVOKED_AT]?.toInstant(),
        createdAt = record[REFRESH_TOKENS.CREATED_AT]!!.toInstant(),
    )
}
