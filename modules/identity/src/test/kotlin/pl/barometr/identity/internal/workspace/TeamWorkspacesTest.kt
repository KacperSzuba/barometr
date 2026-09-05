package pl.barometr.identity.internal.workspace

import org.junit.jupiter.api.Test
import pl.barometr.identity.api.UserId
import pl.barometr.shared.Ids
import pl.barometr.testing.TestClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An organisation's account: who may do what to it, and the two states it must never be
 * left in — without an owner, and with more people in it than seats paid for.
 */
class TeamWorkspacesTest {

    private val clock = TestClock()
    private val workspaces = InMemoryWorkspaces()
    private val invitations = InMemoryWorkspaceInvitations()
    private val properties = WorkspaceProperties(defaultSeats = 3, invitationBaseUrl = "https://barometr.example")

    private val team = TeamWorkspaces(workspaces, invitations, properties, clock)

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
}
