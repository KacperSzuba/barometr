package pl.barometr.identity.internal.auth

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.identity.internal.config.JwtProperties
import pl.barometr.identity.internal.user.InMemoryRefreshTokens
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
    private val userId = Ids.next()

    private lateinit var service: RefreshTokenService

    @BeforeEach
    fun setUp() {
        service = RefreshTokenService(tokens, properties(), clock)
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

    private fun properties() = JwtProperties(
        secret = "test-secret-at-least-thirty-two-bytes-long",
        issuer = "barometr",
        audience = "barometr-web",
        accessTtl = Duration.ofMinutes(15),
        refreshTtl = Duration.ofDays(30),
        refreshGrace = Duration.ofSeconds(15),
    )
}
