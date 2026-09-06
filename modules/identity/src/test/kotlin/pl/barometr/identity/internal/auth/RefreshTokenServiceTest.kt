package pl.barometr.identity.internal.auth

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.identity.internal.config.JwtProperties
import pl.barometr.identity.internal.user.InMemoryRefreshTokens
import pl.barometr.identity.api.UserId
import pl.barometr.identity.internal.user.InMemorySessions
import pl.barometr.identity.internal.workspace.Workspace
import pl.barometr.identity.internal.workspace.WorkspaceId
import pl.barometr.identity.internal.workspace.InMemoryWorkspaces
import pl.barometr.identity.internal.workspace.WorkspacePolicies
import pl.barometr.identity.internal.user.SignedInSession
import pl.barometr.identity.api.UserSessionsRevoked
import pl.barometr.shared.Ids
import pl.barometr.testing.TestClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Refresh-token rotation, which is the most security-sensitive logic in the system
 * and had no tests at all until now.
 *
 * The behaviour worth pinning down is the one that is easy to get subtly wrong: a
 * second refresh with the same token is *either* a browser firing two requests in
 * parallel *or* a stolen token being replayed, and the only thing separating them
 * is how long ago the first one happened.
 */
class RefreshTokenServiceTest {

    private val clock = TestClock()
    private val tokens = InMemoryRefreshTokens()
    private val sessions = InMemorySessions()
    private val workspaces = InMemoryWorkspaces()
    private val policies = WorkspacePolicies(workspaces)
    private val userId = Ids.next()

    private val events = RecordingEvents()

    private lateinit var service: RefreshTokenService

    @BeforeEach
    fun setUp() {
        service = RefreshTokenService(tokens, sessions, policies, properties(), SessionProperties(), events, clock)
    }

    @Test
    fun `rotating a token retires it and issues a different one`() {
        val original = service.issue(userId)

        val rotation = service.rotate(original.raw)

        assertEquals(userId, rotation.userId)
        assertNotEquals(original.raw, rotation.refreshToken.raw)
        assertEquals(original.familyId, rotation.refreshToken.familyId, "a rotation stays in its family")
        assertEquals(clock.instant(), tokens.all.first { it.id == original.id }.usedAt)
    }

    @Test
    fun `an unknown token is refused`() {
        assertFailsWith<InvalidRefreshTokenException> { service.rotate("never-issued") }
    }

    @Test
    fun `an expired token is refused without revoking anything`() {
        val issued = service.issue(userId)
        clock.advanceBy(Duration.ofDays(31))

        assertFailsWith<InvalidRefreshTokenException> { service.rotate(issued.raw) }

        // Expiry is not evidence of theft: the family survives so the user can log
        // in again without every other session being torn down.
        assertTrue(tokens.live().isNotEmpty())
    }

    /**
     * The case that made the previous implementation single-instance. Next.js fires
     * several route-guard requests at once, so two of them routinely present the
     * same token; the second must be served rather than treated as a replay.
     */
    @Test
    fun `a second refresh inside the grace window is served`() {
        val issued = service.issue(userId)

        val first = service.rotate(issued.raw)
        clock.advanceBy(Duration.ofSeconds(5))
        val second = service.rotate(issued.raw)

        assertNotEquals(first.refreshToken.raw, second.refreshToken.raw)
        assertEquals(first.refreshToken.familyId, second.refreshToken.familyId)
        assertNull(tokens.all.first { it.id == issued.id }.revokedAt, "no theft was detected")
        // Both callers hold a usable token, and both are revocable as one family.
        assertEquals(3, tokens.live().size)
    }

    /** `used_at` marks when the window opened, not when the latest caller arrived. */
    @Test
    fun `the grace window does not slide forward on each use`() {
        val issued = service.issue(userId)
        service.rotate(issued.raw)

        clock.advanceBy(Duration.ofSeconds(10))
        service.rotate(issued.raw)

        clock.advanceBy(Duration.ofSeconds(10))
        assertFailsWith<RefreshTokenReuseException> { service.rotate(issued.raw) }
    }

    @Test
    fun `a replay outside the grace window revokes the whole family`() {
        val issued = service.issue(userId)
        val rotated = service.rotate(issued.raw)

        clock.advanceBy(Duration.ofMinutes(5))

        assertFailsWith<RefreshTokenReuseException> { service.rotate(issued.raw) }

        // Every descendant of that login dies, including the successor the
        // legitimate caller is holding: after a replay we cannot tell which of the
        // two is the thief.
        assertTrue(tokens.live().isEmpty())
        assertNotNull(tokens.all.first { it.id == rotated.refreshToken.id }.revokedAt)
    }

    /**
     * The half that was missing. A replay ends every session the account has, and the
     * request that triggered it is recorded as a refused refresh — which is what an
     * expired token looks like too. Unless it is announced, "why was I signed out
     * everywhere" has no answer anywhere in the system.
     */
    @Test
    fun `a replay says so, and says what it was`() {
        val issued = service.issue(userId)
        service.rotate(issued.raw)
        clock.advanceBy(Duration.ofMinutes(5))

        assertFailsWith<RefreshTokenReuseException> { service.rotate(issued.raw) }

        val announced = events.of<UserSessionsRevoked>().single()
        assertEquals(UserSessionsRevoked.RevocationReason.TOKEN_REUSE_DETECTED, announced.reason)
        assertEquals(userId, announced.userId.value)
    }

    @Test
    fun `presenting an already revoked token is treated as theft`() {
        val issued = service.issue(userId)
        service.revokeFamilyOf(issued.raw)

        assertFailsWith<RefreshTokenReuseException> { service.rotate(issued.raw) }
    }

    @Test
    fun `logout revokes every token descending from that login`() {
        val issued = service.issue(userId)
        service.rotate(issued.raw)

        assertEquals(userId, service.revokeFamilyOf(issued.raw))
        assertTrue(tokens.live().isEmpty())
    }

    @Test
    fun `logging out with an unknown token reports nothing to revoke`() {
        assertNull(service.revokeFamilyOf("never-issued"))
    }

    @Test
    fun `a stored token is never the token itself`() {
        val issued = service.issue(userId)

        val stored = tokens.all.single()
        assertNotEquals(issued.raw, stored.tokenHash)
        assertEquals(64, stored.tokenHash.length, "a SHA-256 in hex")
    }

    /**
     * A device nobody has used for longer than a session may go quiet ends at the
     * moment it tries to come back — which is the moment it matters, because the
     * refresh is the only thing that would have revived it.
     */
    @Test
    fun `a session that has gone quiet for too long cannot refresh its way back`() {
        val issued = service.issue(userId)
        sessions.open(
            SignedInSession(
                familyId = issued.familyId,
                userId = userId,
                userAgent = "Mozilla/5.0",
                clientIp = "203.0.113.7",
                createdAt = clock.instant(),
                lastSeenAt = clock.instant(),
            ),
        )

        clock.advanceBy(Duration.ofDays(15))

        assertFailsWith<InvalidRefreshTokenException> { service.rotate(issued.raw) }
        assertTrue(tokens.live().isEmpty(), "the family goes with the session")
        assertNotNull(sessions.byFamily(issued.familyId)?.revokedAt)
    }

    /** Ended for a different reason, and the person is owed the difference. */
    @Test
    fun `a session ended for going quiet says that, not theft`() {
        val issued = service.issue(userId)
        sessions.open(
            SignedInSession(
                familyId = issued.familyId,
                userId = userId,
                userAgent = "Mozilla/5.0",
                clientIp = "203.0.113.7",
                createdAt = clock.instant(),
                lastSeenAt = clock.instant(),
            ),
        )
        clock.advanceBy(Duration.ofDays(15))

        assertFailsWith<InvalidRefreshTokenException> { service.rotate(issued.raw) }

        assertEquals(
            UserSessionsRevoked.RevocationReason.IDLE,
            events.of<UserSessionsRevoked>().single().reason,
        )
    }

    @Test
    fun `a refresh moves the session's last-seen mark and the address with it`() {
        val issued = service.issue(userId)
        sessions.open(
            SignedInSession(
                familyId = issued.familyId,
                userId = userId,
                userAgent = "Mozilla/5.0",
                clientIp = "203.0.113.7",
                createdAt = clock.instant(),
                lastSeenAt = clock.instant(),
            ),
        )

        clock.advanceBy(Duration.ofDays(1))
        service.rotate(issued.raw, ClientFingerprint("Mozilla/5.0", "198.51.100.9"))

        val session = assertNotNull(sessions.byFamily(issued.familyId))
        assertEquals(clock.instant(), session.lastSeenAt)
        assertEquals("198.51.100.9", session.clientIp, "a laptop does travel")
    }

    @Test
    fun `logging out closes the session as well as the tokens`() {
        val issued = service.issue(userId)
        sessions.open(
            SignedInSession(
                familyId = issued.familyId,
                userId = userId,
                userAgent = null,
                clientIp = null,
                createdAt = clock.instant(),
                lastSeenAt = clock.instant(),
            ),
        )

        service.revokeFamilyOf(issued.raw)

        assertNotNull(sessions.byFamily(issued.familyId)?.revokedAt)
        assertEquals(emptyList(), sessions.liveFor(userId))
    }

    /**
     * The institutional customer's second question, answered: "can we have sessions that
     * end sooner than your default". The deployment says fourteen days; this workspace
     * says eight hours, and eight hours is what the session gets.
     */
    @Test
    fun `a workspace that asks for shorter sessions gets them`() {
        val workspace = workspaces.create(
            Workspace(
                id = WorkspaceId(Ids.next()),
                name = "Kancelaria Nowak",
                seats = 5,
                requireTwoFactor = false,
                sessionIdleTimeout = Duration.ofHours(8),
                createdAt = clock.instant(),
            ),
            UserId(userId),
            clock.instant(),
        )
        assertEquals(Duration.ofHours(8), workspace.sessionIdleTimeout)

        val issued = service.issue(userId)
        sessions.open(
            SignedInSession(
                familyId = issued.familyId,
                userId = userId,
                userAgent = "Mozilla/5.0",
                clientIp = null,
                createdAt = clock.instant(),
                lastSeenAt = clock.instant(),
            ),
        )

        // Well inside the deployment's fourteen days, and well past the workspace's eight
        // hours.
        clock.advanceBy(Duration.ofHours(9))

        assertFailsWith<InvalidRefreshTokenException> { service.rotate(issued.raw) }
        assertTrue(tokens.live().isEmpty())
    }

    private fun properties() = JwtProperties(
        secret = "test-secret-at-least-thirty-two-bytes-long",
        issuer = "barometr",
        audience = "barometr-web",
        accessTtl = Duration.ofMinutes(15),
        refreshTtl = Duration.ofDays(30),
        refreshGrace = Duration.ofSeconds(15),
    )
}
