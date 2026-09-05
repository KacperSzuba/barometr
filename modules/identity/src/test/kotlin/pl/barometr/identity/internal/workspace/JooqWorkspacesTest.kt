package pl.barometr.identity.internal.workspace

import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.identity.api.UserId
import pl.barometr.identity.internal.jooq.tables.references.USERS
import pl.barometr.identity.internal.jooq.tables.references.WORKSPACE
import pl.barometr.identity.internal.jooq.tables.references.WORKSPACE_MEMBER
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Workspaces against a real Postgres, because the parts worth checking are the
 * database's: an interval that survives the round trip, a role the schema refuses, and
 * the two policy queries that run on every sign-in.
 */
class JooqWorkspacesTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val workspaces = JooqWorkspaces(dsl)

    // Not `lateinit`: Kotlin refuses it on a value class, and these are ids.
    private var ewa: UserId = UserId(Ids.next())
    private var marek: UserId = UserId(Ids.next())

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(WORKSPACE).execute()
        dsl.deleteFrom(USERS).execute()
        ewa = UserId(user("ewa@example.test"))
        marek = UserId(user("marek@example.test"))
    }

    @Test
    fun `a workspace comes back as it was created, with its owner in it`() {
        val created = create("Kancelaria Nowak", idleTimeout = Duration.ofHours(8))

        val stored = workspaces.byId(created.id)

        assertEquals(created.copy(sessionIdleTimeout = Duration.ofHours(8)), stored)
        assertEquals(WorkspaceRole.OWNER, workspaces.membership(created.id, ewa)?.role)
        assertEquals(1, workspaces.countOwners(created.id))
    }

    @Test
    fun `an idle timeout survives the round trip as the interval it was`() {
        val created = create("Kancelaria Nowak", idleTimeout = Duration.ofDays(2).plusHours(3))

        assertEquals(Duration.ofDays(2).plusHours(3), workspaces.byId(created.id)?.sessionIdleTimeout)
    }

    @Test
    fun `a workspace with no opinion about sessions says so`() {
        val created = create("Kancelaria Nowak")

        assertNull(workspaces.byId(created.id)?.sessionIdleTimeout)
        assertNull(workspaces.strictestIdleTimeout(ewa))
    }

    /** Somebody in two workspaces is subject to both, and the stricter one is what means anything. */
    @Test
    fun `the shortest timeout any of an account's workspaces chose is the one that counts`() {
        val strict = create("Kancelaria Nowak", idleTimeout = Duration.ofHours(8))
        val relaxed = create("Fundacja Prawo i Klimat", idleTimeout = Duration.ofDays(7))
        workspaces.addMember(WorkspaceMembership(relaxed.id, ewa, WorkspaceRole.MEMBER, clock.instant()))

        assertEquals(Duration.ofHours(8), workspaces.strictestIdleTimeout(ewa))
        assertEquals(strict.id, workspaces.byId(strict.id)?.id)
    }

    @Test
    fun `a second factor is required when any one workspace requires it`() {
        val relaxed = create("Fundacja Prawo i Klimat")
        val strict = create("Kancelaria Nowak")
        workspaces.updatePolicy(strict.id, requireTwoFactor = true, idleTimeout = null)

        assertTrue(workspaces.anyRequiresTwoFactor(ewa))
        assertTrue(!workspaces.anyRequiresTwoFactor(marek), "and nobody else's account is touched")
        assertEquals(2, workspaces.membershipsOf(ewa).size)
        assertEquals(relaxed.id, workspaces.membershipsOf(ewa).first().workspace)
    }

    @Test
    fun `a member added twice is one member`() {
        val workspace = create("Kancelaria Nowak")

        assertTrue(workspaces.addMember(WorkspaceMembership(workspace.id, marek, WorkspaceRole.MEMBER, clock.instant())))
        assertTrue(
            !workspaces.addMember(WorkspaceMembership(workspace.id, marek, WorkspaceRole.ADMIN, clock.instant())),
            "the second is not an error and does not change the role",
        )
        assertEquals(WorkspaceRole.MEMBER, workspaces.membership(workspace.id, marek)?.role)
        assertEquals(2, workspaces.countMembers(workspace.id))
    }

    @Test
    fun `removing a member frees the seat and leaves the rest alone`() {
        val workspace = create("Kancelaria Nowak")
        workspaces.addMember(WorkspaceMembership(workspace.id, marek, WorkspaceRole.MEMBER, clock.instant()))

        assertTrue(workspaces.removeMember(workspace.id, marek))
        assertEquals(1, workspaces.countMembers(workspace.id))
        assertEquals(WorkspaceRole.OWNER, workspaces.membership(workspace.id, ewa)?.role)
    }

    /** The closed vocabulary is the database's as well as the code's. */
    @Test
    fun `a role no code knows about is refused by the database`() {
        val workspace = create("Kancelaria Nowak")

        assertFailsWith<DataAccessException> {
            dsl.insertInto(WORKSPACE_MEMBER)
                .set(WORKSPACE_MEMBER.WORKSPACE_ID, workspace.id.value)
                .set(WORKSPACE_MEMBER.USER_ID, marek.value)
                .set(WORKSPACE_MEMBER.ROLE, "wlasciciel")
                .set(WORKSPACE_MEMBER.JOINED_AT, clock.instant().atOffset(ZoneOffset.UTC))
                .execute()
        }
    }

    @Test
    fun `an idle timeout nobody could mean is refused by the database`() {
        assertFailsWith<DataAccessException> {
            create("Kancelaria Nowak", idleTimeout = Duration.ofSeconds(30))
        }
    }

    /**
     * A deleted account takes its membership and nothing else: the workspace is the
     * organisation's, not the person's, and the rest of the team keeps working.
     *
     * The state this cannot prevent — deleting the *last* member and stranding a
     * workspace nobody can reach — is guarded on the way in by [TeamWorkspaces], which
     * refuses to remove the last owner. Deleting the account itself is the one path
     * around that, and it belongs to the account-deletion work rather than here.
     */
    @Test
    fun `deleting an account leaves the workspace without that member`() {
        val workspace = create("Kancelaria Nowak")
        workspaces.addMember(WorkspaceMembership(workspace.id, marek, WorkspaceRole.MEMBER, clock.instant()))

        dsl.deleteFrom(USERS).where(USERS.ID.eq(marek.value)).execute()

        assertEquals(1, workspaces.countMembers(workspace.id))
        assertNotNull(workspaces.byId(workspace.id))
        assertNull(workspaces.membership(workspace.id, marek))
    }

    private fun create(name: String, idleTimeout: Duration? = null): Workspace =
        workspaces.create(
            Workspace(
                id = WorkspaceId(Ids.next()),
                name = name,
                seats = 5,
                requireTwoFactor = false,
                sessionIdleTimeout = idleTimeout,
                createdAt = clock.instant(),
            ),
            ewa,
            clock.instant(),
        )

    private fun user(email: String): UUID {
        val id = Ids.next()
        dsl.insertInto(USERS)
            .set(USERS.ID, id)
            .set(USERS.EMAIL, email)
            .set(USERS.PASSWORD_HASH, "not-a-hash")
            .set(USERS.ENABLED, true)
            .set(USERS.CREATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .execute()
        return id
    }
}
