package pl.barometr.identity.internal.user

import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.Role
import pl.barometr.identity.internal.jooq.tables.references.USERS
import pl.barometr.identity.internal.jooq.tables.references.USER_ROLES
import java.time.ZoneOffset
import java.util.UUID

/** [Users] over jOOQ. SQL only — no policy, no events. */
@Repository
@Transactional(readOnly = true)
class JooqUsers(private val dsl: DSLContext) : Users {

    /**
     * A user's roles come back with the user, in one round trip.
     *
     * `MULTISET` rather than a second query or a join: a join would repeat every
     * column of the user once per role and leave the caller to fold the rows back
     * together, and a second query would put a round trip on the login path. This
     * nests the correlated rows and converts them where the types are still known,
     * so an unknown role fails here rather than as a surprise authority later.
     */
    private val roles = DSL.multiset(
        DSL.select(USER_ROLES.ROLE)
            .from(USER_ROLES)
            .where(USER_ROLES.USER_ID.eq(USERS.ID)),
    ).convertFrom { result ->
        result.mapNotNull { row -> row.value1()?.let(Role::ofName) }.toSet()
    }

    override fun byId(id: UUID): User? = selectUser().where(USERS.ID.eq(id)).fetchOne(::toUser)

    override fun byEmail(email: String): User? =
        selectUser().where(USERS.EMAIL.eq(email)).fetchOne(::toUser)

    override fun existsWithEmail(email: String): Boolean =
        dsl.fetchExists(USERS, USERS.EMAIL.eq(email))

    @Transactional
    override fun add(user: User): User {
        dsl.insertInto(USERS)
            .set(USERS.ID, user.id)
            .set(USERS.EMAIL, user.email)
            .set(USERS.PASSWORD_HASH, user.passwordHash)
            .set(USERS.ENABLED, user.enabled)
            .set(USERS.CREATED_AT, user.createdAt.atOffset(ZoneOffset.UTC))
            .execute()

        // In the same transaction as the user: a user with no roles is not a user
        // anyone can do anything as, and the two halves must arrive together.
        user.roles.forEach { role ->
            dsl.insertInto(USER_ROLES)
                .set(USER_ROLES.USER_ID, user.id)
                .set(USER_ROLES.ROLE, role.name)
                .set(USER_ROLES.GRANTED_AT, user.createdAt.atOffset(ZoneOffset.UTC))
                .execute()
        }

        return user
    }

    private fun selectUser() = dsl.select(
        USERS.ID,
        USERS.EMAIL,
        USERS.PASSWORD_HASH,
        USERS.ENABLED,
        USERS.CREATED_AT,
        roles,
    ).from(USERS)

    private fun toUser(record: Record) = User(
        id = record[USERS.ID]!!,
        email = record[USERS.EMAIL]!!,
        passwordHash = record[USERS.PASSWORD_HASH]!!,
        roles = record[roles],
        enabled = record[USERS.ENABLED]!!,
        createdAt = record[USERS.CREATED_AT]!!.toInstant(),
    )
}
