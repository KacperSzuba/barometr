package pl.barometr.identity.internal.workspace

import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.junit.jupiter.api.Test
import pl.barometr.identity.api.Role
import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.UserLookup
import pl.barometr.identity.api.UserSnapshot
import pl.barometr.identity.api.WorkspaceInvitationIssued
import pl.barometr.shared.Ids
import pl.barometr.testing.TestClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Offering a seat and taking one.
 *
 * The rule that matters most is the one that keeps a forwarded link from being a way
 * into somebody else's workspace: an invitation names an address, and taking it means
 * being signed in as the account that holds that address.
 */
class TeamInvitationsTest {

    private val clock = TestClock()
    private val workspaces = InMemoryWorkspaces()
    private val invitations = InMemoryWorkspaceInvitations()
    private val properties = WorkspaceProperties(defaultSeats = 2, invitationBaseUrl = "https://barometr.example")
    private val team = TeamWorkspaces(workspaces, invitations, properties, clock)
    private val events = RecordingEvents()

    private val service = TeamInvitations(invitations, workspaces, team, Accounts, properties, events, clock)

    private val workspace by lazy { team.createWorkspace(Accounts.EWA, "Kancelaria Nowak").id }

    @Test
    fun `an invitation goes out with a link, and says who sent it and to what`() {
        service.invite(Accounts.EWA, workspace, "Marek@Example.test", WorkspaceRole.MEMBER)

        val announced = events.of<WorkspaceInvitationIssued>().single()
        assertEquals("marek@example.test", announced.email, "an address is held lowercase everywhere")
        assertEquals("Kancelaria Nowak", announced.workspaceName)
        assertEquals("ewa@example.test", announced.invitedBy)
        assertTrue(announced.acceptUrl.startsWith("https://barometr.example/zaproszenia/"), announced.acceptUrl)
    }

    @Test
    fun `the token never appears anywhere but the link`() {
        service.invite(Accounts.EWA, workspace, "marek@example.test", WorkspaceRole.MEMBER)

        val token = events.of<WorkspaceInvitationIssued>().single().acceptUrl.substringAfterLast('/')
        val stored = service.openInvitations(Accounts.EWA, workspace).single()

        assertTrue(stored.tokenHash.length == 64, "only the hash is kept")
        assertTrue(!stored.tokenHash.contains(token))
    }

    @Test
    fun `the invited account takes the seat`() {
        service.invite(Accounts.EWA, workspace, "marek@example.test", WorkspaceRole.ADMIN)

        val membership = service.acceptInvitation(Accounts.MAREK, tokenIssued())

        assertEquals(workspace, membership.workspace)
        assertEquals(WorkspaceRole.ADMIN, membership.role)
        assertEquals(WorkspaceRole.ADMIN, team.membershipOf(Accounts.MAREK, workspace).role)
    }

    /** A link forwarded to a colleague must not make them a member of somebody else's workspace. */
    @Test
    fun `a link sent to one address does not work for another account`() {
        service.invite(Accounts.EWA, workspace, "marek@example.test", WorkspaceRole.MEMBER)

        assertFailsWith<InvitationNotForThisAccountException> {
            service.acceptInvitation(Accounts.OBCY, tokenIssued())
        }
    }

    @Test
    fun `a seat is taken once`() {
        service.invite(Accounts.EWA, workspace, "marek@example.test", WorkspaceRole.MEMBER)
        val token = tokenIssued()
        service.acceptInvitation(Accounts.MAREK, token)

        assertFailsWith<UnknownInvitationException> { service.acceptInvitation(Accounts.MAREK, token) }
    }

    @Test
    fun `an expired link is worth nothing`() {
        service.invite(Accounts.EWA, workspace, "marek@example.test", WorkspaceRole.MEMBER)
        val token = tokenIssued()

        clock.advanceBy(properties.invitationTtl.plus(Duration.ofMinutes(1)))

        assertFailsWith<UnknownInvitationException> { service.acceptInvitation(Accounts.MAREK, token) }
    }

    @Test
    fun `a revoked link is worth nothing either, and gives the seat back`() {
        service.invite(Accounts.EWA, workspace, "marek@example.test", WorkspaceRole.MEMBER)
        val token = tokenIssued()
        val invitation = service.openInvitations(Accounts.EWA, workspace).single()

        service.revokeInvitation(Accounts.EWA, workspace, invitation.id)

        assertFailsWith<UnknownInvitationException> { service.acceptInvitation(Accounts.MAREK, token) }
        assertEquals(1, team.taken(workspace), "the seat is free again")
    }

    /** An invitation that has gone out is a seat somebody has already been promised. */
    @Test
    fun `an open invitation counts against the seats`() {
        service.invite(Accounts.EWA, workspace, "marek@example.test", WorkspaceRole.MEMBER)

        assertEquals(2, team.taken(workspace))
        assertFailsWith<NoSeatsLeftException> {
            service.invite(Accounts.EWA, workspace, "trzeci@example.test", WorkspaceRole.MEMBER)
        }
    }

    @Test
    fun `only an administrator may invite or revoke`() {
        service.invite(Accounts.EWA, workspace, "marek@example.test", WorkspaceRole.MEMBER)
        service.acceptInvitation(Accounts.MAREK, tokenIssued())

        assertFailsWith<NotWorkspaceAdministratorException> {
            service.invite(Accounts.MAREK, workspace, "trzeci@example.test", WorkspaceRole.MEMBER)
        }
    }

    private fun tokenIssued() = events.of<WorkspaceInvitationIssued>().last().acceptUrl.substringAfterLast('/')

    private object Accounts : UserLookup {
        val EWA = UserId(Ids.next())
        val MAREK = UserId(Ids.next())
        val OBCY = UserId(Ids.next())

        private val addresses = mapOf(EWA to "ewa@example.test", MAREK to "marek@example.test", OBCY to "obcy@example.test")

        override fun findById(id: UserId): UserSnapshot? =
            addresses[id]?.let { UserSnapshot(id, it, setOf(Role.USER), enabled = true) }

        override fun findByEmail(email: String): UserSnapshot? =
            addresses.entries.firstOrNull { it.value == email }?.let { findById(it.key) }
    }

    private class RecordingEvents : ApplicationEventPublisher {
        private val published = mutableListOf<Any>()

        override fun publishEvent(event: ApplicationEvent) = publishEvent(event as Any)

        override fun publishEvent(event: Any) {
            published += event
        }

        inline fun <reified T> of(): List<T> = published.filterIsInstance<T>()
    }
}
