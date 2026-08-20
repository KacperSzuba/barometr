package pl.barometr.identity.internal.user

import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.identity.api.Role
import pl.barometr.identity.internal.jooq.tables.references.USERS
import pl.barometr.identity.internal.jooq.tables.references.USER_ROLES
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What storage owes the identity services, checked against a real Postgres.
 *
 * The services themselves are tested against in-memory fakes, which is right: their
 * subject is policy. What cannot be faked is whether the schema actually enforces
 * what it claims to — and roles only stopped being a comma-separated string because
 * the constraints below are real.
 */
class JooqUsersTest {

    private val dsl = PostgresTestDatabase.dsl()
    private val users = JooqUsers(dsl)

    @BeforeEach
    fun setUp() {
        // `user_roles` goes with the user: the foreign key cascades.
        dsl.deleteFrom(USERS).execute()
    }

    @Test
    fun `a user's roles are stored as rows and come back as roles`() {
        val stored = users.add(newUser(roles = setOf(Role.USER, Role.OPERATOR)))

        assertEquals(setOf(Role.USER, Role.OPERATOR), users.byId(stored.id)!!.roles)
        assertEquals(setOf(Role.USER, Role.OPERATOR), users.byEmail(stored.email)!!.roles)
        assertEquals(2, dsl.fetchCount(USER_ROLES, USER_ROLES.USER_ID.eq(stored.id)))
    }

    /**
     * The reason this is a table. `roles = 'OPERTAOR'` was a valid string; a row
     * naming a role nothing checks for is refused, at the only layer that cannot be
     * bypassed by a script, a console or a future service.
     */
    @Test
    fun `a role no code knows about is refused by the database`() {
        val stored = users.add(newUser())

        val failure = assertFailsWith<DataAccessException> {
            dsl.insertInto(USER_ROLES)
                .set(USER_ROLES.USER_ID, stored.id)
                .set(USER_ROLES.ROLE, "OPERTAOR")
                .set(USER_ROLES.GRANTED_AT, Instant.now().atOffset(ZoneOffset.UTC))
                .execute()
        }
        assertTrue(failure.message!!.contains("ck_user_roles_known"))
    }

    /** Granting the same role twice is the primary key's problem, not the caller's. */
    @Test
    fun `a role cannot be granted to the same user twice`() {
        val stored = users.add(newUser())

        assertFailsWith<DataAccessException> {
            dsl.insertInto(USER_ROLES)
                .set(USER_ROLES.USER_ID, stored.id)
                .set(USER_ROLES.ROLE, Role.USER.name)
                .set(USER_ROLES.GRANTED_AT, Instant.now().atOffset(ZoneOffset.UTC))
                .execute()
        }
    }

    /**
     * The question the old column could not answer without reading every row and
     * splitting strings.
     */
    @Test
    fun `the operators can be listed`() {
        users.add(newUser(email = "czytelnik@example.test"))
        val operator = users.add(newUser(email = "operator@example.test", roles = setOf(Role.USER, Role.OPERATOR)))

        val operators = dsl.select(USER_ROLES.USER_ID)
            .from(USER_ROLES)
            .where(USER_ROLES.ROLE.eq(Role.OPERATOR.name))
            .fetch(USER_ROLES.USER_ID)

        assertEquals(listOf(operator.id), operators)
    }

    @Test
    fun `an unknown user is absent rather than empty`() {
        assertNull(users.byId(Ids.next()))
        assertNull(users.byEmail("nikt@example.test"))
    }

    private fun newUser(
        email: String = "poslanka@example.test",
        roles: Set<Role> = setOf(Role.USER),
    ) = User(
        id = Ids.next(),
        email = email,
        passwordHash = "irrelevant-here",
        roles = roles,
        createdAt = Instant.parse("2026-08-21T10:00:00Z"),
    )
}
