package pl.barometr.identity.internal.user

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.internal.jooq.tables.references.REFRESH_TOKENS
import pl.barometr.identity.internal.jooq.tables.references.SESSION
import pl.barometr.identity.internal.jooq.tables.references.TRUSTED_DEVICE
import java.time.Instant
import java.time.ZoneOffset

/**
 * Deletes the credentials nobody can use any more. SQL only.
 *
 * Only what is already dead: a revoked session, an expired refresh token, a trusted
 * device whose month is up. A live session is not retention's business however old it is,
 * and deleting one would sign somebody out for reasons of tidiness.
 */
@Repository
class CredentialRetention(private val dsl: DSLContext) {

    @Transactional
    fun deleteOlderThan(cutoff: Instant): Int {
        val at = cutoff.atOffset(ZoneOffset.UTC)

        val tokens = dsl.deleteFrom(REFRESH_TOKENS)
            .where(REFRESH_TOKENS.EXPIRES_AT.lt(at))
            .or(REFRESH_TOKENS.REVOKED_AT.lt(at))
            .execute()

        val sessions = dsl.deleteFrom(SESSION).where(SESSION.REVOKED_AT.lt(at)).execute()

        val devices = dsl.deleteFrom(TRUSTED_DEVICE)
            .where(TRUSTED_DEVICE.EXPIRES_AT.lt(at))
            .or(TRUSTED_DEVICE.REVOKED_AT.lt(at))
            .execute()

        return tokens + sessions + devices
    }
}
