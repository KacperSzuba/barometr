package pl.barometr.identity.internal.user

import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.internal.jooq.tables.references.USERS
import java.time.ZoneOffset
import java.util.UUID

/** [Users] over jOOQ. SQL only — no policy, no events. */
@Repository
@Transactional(readOnly = true)
class JooqUsers(private val dsl: DSLContext) : Users {

    override fun byId(id: UUID): User? =
        dsl.selectFrom(USERS).where(USERS.ID.eq(id)).fetchOne(::toUser)

    override fun byEmail(email: String): User? =
        dsl.selectFrom(USERS).where(USERS.EMAIL.eq(email)).fetchOne(::toUser)

    override fun existsWithEmail(email: String): Boolean =
        dsl.fetchExists(USERS, USERS.EMAIL.eq(email))

    @Transactional
    override fun add(user: User): User {
        dsl.insertInto(USERS)
            .set(USERS.ID, user.id)
            .set(USERS.EMAIL, user.email)
            .set(USERS.PASSWORD_HASH, user.passwordHash)
            .set(USERS.ROLES, encodeRoles(user.roles))
            .set(USERS.ENABLED, user.enabled)
            .set(USERS.CREATED_AT, user.createdAt.atOffset(ZoneOffset.UTC))
            .execute()
        return user
    }

    private fun toUser(record: Record) = User(
        id = record[USERS.ID]!!,
        email = record[USERS.EMAIL]!!,
        passwordHash = record[USERS.PASSWORD_HASH]!!,
        roles = decodeRoles(record[USERS.ROLES]!!),
        enabled = record[USERS.ENABLED]!!,
        createdAt = record[USERS.CREATED_AT]!!.toInstant(),
    )

    // The column holds a comma-separated list. Both directions live here so that the
    // encoding is a fact about storage rather than about a user; see [User].
    private fun encodeRoles(roles: Set<String>): String = roles.joinToString(",")

    private fun decodeRoles(stored: String): Set<String> =
        stored.split(",").map(String::trim).filter(String::isNotEmpty).toSet()
}
