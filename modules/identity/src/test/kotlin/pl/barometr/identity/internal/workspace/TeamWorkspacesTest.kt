package pl.barometr.identity.internal.workspace

import org.junit.jupiter.api.Test
import pl.barometr.identity.api.UserId
import pl.barometr.identity.internal.user.InMemoryUsers
import pl.barometr.identity.internal.user.User
import pl.barometr.shared.Ids
import pl.barometr.testing.TestClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An organisation's account: who may do what to it, and the two states it must never be
 * left in — without an owner, and with more people in it than seats paid for.
 */
class TeamWorkspacesTest {

    private val clock = TestClock()
    private val workspaces = InMemoryWorkspaces()
    private val invitations = InMemoryWorkspaceInvitations()
    private val accounts = InMemoryUsers()
    private val properties = WorkspaceProperties(defaultSeats = 3, invitationBaseUrl = "https://barometr.example")

    private val team = TeamWorkspaces(workspaces, accounts, invitations, properties, clock)

    private val ewa = UserId(Ids.next())
    private val marek = UserId(Ids.next())
    private val obcy = UserId(Ids.next())

    @Test
    fun `whoever creates a workspace owns it`() {
        val workspace = team.createWorkspace(ewa, "Kancelaria Nowak")

        assertEquals("Kancelaria Nowak", workspace.name)
        assertEquals(3, workspace.seats)
        assertEquals(WorkspaceRole.OWNER, team.membershipOf(ewa, workspace.id).role)
        assertEquals(1, team.taken(workspace.id))
    }

    @Test
    fun `somebody who is not in it cannot see it, and is not told it exists`() {
        val workspace = team.createWorkspace(ewa, "Kancelaria Nowak")

        assertFailsWith<UnknownWorkspaceException> { team.readWorkspace(obcy, workspace.id) }
        assertFailsWith<UnknownWorkspaceException> { team.membersOf(obcy, workspace.id) }
    }

    /**
     * Forbidden rather than not-found: a member can see the workspace, so pretending it
     * is absent would be a lie they can check.
     */
    @Test
    fun `an ordinary member cannot set policy or move anybody`() {
        val workspace = team.createWorkspace(ewa, "Kancelaria Nowak")
        join(workspace.id, marek, WorkspaceRole.MEMBER)

        assertFailsWith<NotWorkspaceAdministratorException> { team.setPolicy(marek, workspace.id, true, null) }
        assertFailsWith<NotWorkspaceAdministratorException> {
            team.changeRole(marek, workspace.id, ewa, WorkspaceRole.MEMBER)
        }
    }

    @Test
    fun `an administrator sets the policies an institutional customer asks about`() {
        val workspace = team.createWorkspace(ewa, "Kancelaria Nowak")
        join(workspace.id, marek, WorkspaceRole.ADMIN)

        val updated = team.setPolicy(marek, workspace.id, requireTwoFactor = true, idleTimeout = Duration.ofHours(8))

        assertTrue(updated.requireTwoFactor)
        assertEquals(Duration.ofHours(8), updated.sessionIdleTimeout)
    }

    @Test
    fun `the last owner cannot be demoted or removed`() {
        val workspace = team.createWorkspace(ewa, "Kancelaria Nowak")
        join(workspace.id, marek, WorkspaceRole.ADMIN)

        assertFailsWith<LastOwnerException> { team.changeRole(ewa, workspace.id, ewa, WorkspaceRole.MEMBER) }
        assertFailsWith<LastOwnerException> { team.removeMember(ewa, workspace.id, ewa) }
    }

    @Test
    fun `an owner who is not the last one may step down`() {
        val workspace = team.createWorkspace(ewa, "Kancelaria Nowak")
        join(workspace.id, marek, WorkspaceRole.OWNER)

        team.changeRole(ewa, workspace.id, ewa, WorkspaceRole.MEMBER)

        assertEquals(WorkspaceRole.MEMBER, team.membershipOf(ewa, workspace.id).role)
    }

    /** Leaving is not a favour an administrator grants. */
    @Test
    fun `anybody may remove themselves`() {
        val workspace = team.createWorkspace(ewa, "Kancelaria Nowak")
        join(workspace.id, marek, WorkspaceRole.MEMBER)

        team.removeMember(marek, workspace.id, marek)

        assertFailsWith<UnknownWorkspaceException> { team.readWorkspace(marek, workspace.id) }
        assertEquals(1, team.taken(workspace.id))
    }

    @Test
    fun `seats cannot be sold back below what is already in use`() {
        val workspace = team.createWorkspace(ewa, "Kancelaria Nowak")
        join(workspace.id, marek, WorkspaceRole.MEMBER)

        assertFailsWith<NoSeatsLeftException> { team.setSeats(ewa, workspace.id, 1) }
        assertEquals(2, team.setSeats(ewa, workspace.id, 2).seats)
    }

    @Test
    fun `an account may be in more than one workspace`() {
        val first = team.createWorkspace(ewa, "Kancelaria Nowak")
        val second = team.createWorkspace(marek, "Fundacja Prawo i Klimat")
        join(second.id, ewa, WorkspaceRole.MEMBER)

        assertEquals(
            listOf(first.id to WorkspaceRole.OWNER, second.id to WorkspaceRole.MEMBER),
            team.workspacesOf(ewa).map { it.workspace to it.role },
        )
    }

    private fun join(workspace: WorkspaceId, user: UserId, role: WorkspaceRole) {
        workspaces.addMember(WorkspaceMembership(workspace, user, role, clock.instant()))
    }

    /**
     * A members list is read by an administrator deciding who to remove, and three
     * identifiers is not enough to decide anything: the address is the only thing on the
     * row a person recognises.
     */
    @Test
    fun `a members list names everybody on it`() {
        accounts.add(account(ewa, "ewa@example.test"))
        accounts.add(account(marek, "marek@example.test"))
        val workspace = team.createWorkspace(ewa, "Enerpol")
        workspaces.addMember(WorkspaceMembership(workspace.id, marek, WorkspaceRole.MEMBER, clock.instant()))

        val named = team.namedMembersOf(ewa, workspace.id)

        assertEquals(
            setOf("ewa@example.test", "marek@example.test"),
            named.map { it.email }.toSet(),
        )
    }

    /**
     * Closing an account is a route this system implements, and the membership row can
     * outlive it. That row is exactly the one somebody needs to see in order to remove
     * it, so an unresolvable address drops the name rather than the member.
     */
    @Test
    fun `a member whose account is gone still appears, without a name`() {
        accounts.add(account(ewa, "ewa@example.test"))
        val workspace = team.createWorkspace(ewa, "Enerpol")
        workspaces.addMember(WorkspaceMembership(workspace.id, marek, WorkspaceRole.MEMBER, clock.instant()))

        val named = team.namedMembersOf(ewa, workspace.id)

        assertEquals(2, named.size)
        assertNull(named.single { it.user == marek }.email)
    }

    private fun account(id: UserId, email: String) =
        User(id = id.value, email = email, passwordHash = "irrelevant", createdAt = clock.instant())

}
