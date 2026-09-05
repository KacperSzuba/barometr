package pl.barometr.identity.internal.auth

import org.springframework.security.oauth2.jwt.JwtValidationException
import org.junit.jupiter.api.Test
import pl.barometr.identity.api.JwtClaims
import pl.barometr.identity.api.Role
import pl.barometr.identity.internal.config.JwtConfig
import pl.barometr.identity.internal.config.JwtProperties
import pl.barometr.identity.internal.user.User
import pl.barometr.shared.Ids
import pl.barometr.testing.TestClock
import java.time.Duration
import java.util.UUID
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * That a minted access token says what the resource server is about to check.
 *
 * Encoder and decoder are the real ones, built by [JwtConfig] exactly as the
 * application builds them: a test against a hand-rolled JWT would prove that the
 * test can write a JWT.
 */
class TokenServiceTest {

    /** The session every minted token names; the claim is asserted below. */
    private val session: UUID = Ids.next()

    // Real time, not a fixed instant: Nimbus validates `exp` against the system
    // clock, which no test can hand it. What the injected clock controls here is
    // when the token claims to have been issued.
    private val clock = TestClock(Instant.now())
    private val properties = properties()
    private val config = JwtConfig(properties)
    private val service = TokenService(config.jwtEncoder(), properties, clock)

    @Test
    fun `the access token carries the claims the resource server checks`() {
        val user = user(roles = setOf(Role.USER, Role.OPERATOR))

        val token = service.createAccessToken(user, session)
        val decoded = config.jwtDecoder().decode(token.value)

        assertEquals(user.id.toString(), decoded.subject)
        // Read as a claim, not through `Jwt.getIssuer()`: this issuer is a bare
        // name rather than a URL, and that accessor insists on converting it to one.
        assertEquals("barometr", decoded.getClaimAsString("iss"))
        assertEquals(listOf("barometr-web"), decoded.audience)
        assertEquals(user.email, decoded.getClaimAsString(JwtClaims.EMAIL))
        assertEquals(listOf("USER", "OPERATOR"), decoded.getClaimAsStringList(JwtClaims.ROLES))
        assertEquals(session.toString(), decoded.getClaimAsString(JwtClaims.SESSION))
        assertEquals(false, decoded.getClaimAsBoolean(JwtClaims.ENROLMENT_REQUIRED))
    }

    /**
     * The claim the application's filter chain acts on: a caller whose workspace insists
     * on a second factor they have not set up reaches the enrolment routes and nothing
     * else. It is a claim rather than a lookup because the alternative is a database read
     * on every request.
     */
    @Test
    fun `a token can say that its holder still has to set a second factor up`() {
        val token = service.createAccessToken(user(), session, mustEnrolTwoFactor = true)

        assertEquals(true, config.jwtDecoder().decode(token.value).getClaimAsBoolean(JwtClaims.ENROLMENT_REQUIRED))
    }

    @Test
    fun `the token expires after the configured lifetime`() {
        val token = service.createAccessToken(user(), session)
        val decoded = config.jwtDecoder().decode(token.value)

        assertEquals(properties.accessTtl.seconds, token.expiresInSeconds)
        assertEquals(
            properties.accessTtl,
            Duration.between(decoded.issuedAt, decoded.expiresAt),
        )
    }

    /**
     * The audience check is the one most often left out: the default validator
     * covers `exp`, `nbf` and `iss` and stops there, so a token minted for another
     * service by the same issuer would otherwise be accepted here.
     */
    @Test
    fun `a token minted for another audience is rejected`() {
        val otherAudience = properties.copy(audience = "some-other-service")
        val foreign = TokenService(JwtConfig(otherAudience).jwtEncoder(), otherAudience, clock)
            .createAccessToken(user(), session)

        val failure = assertFailsWith<JwtValidationException> {
            config.jwtDecoder().decode(foreign.value)
        }
        assertTrue(failure.message!!.contains("audience"), failure.message!!)
    }

    @Test
    fun `a token signed with another secret is rejected`() {
        val foreignKey = properties.copy(secret = "a-completely-different-secret-of-enough-length")
        val foreign = TokenService(JwtConfig(foreignKey).jwtEncoder(), foreignKey, clock)
            .createAccessToken(user(), session)

        assertFailsWith<org.springframework.security.oauth2.jwt.BadJwtException> {
            config.jwtDecoder().decode(foreign.value)
        }
    }

    private fun user(roles: Set<Role> = setOf(Role.USER)) = User(
        id = Ids.next(),
        email = "poslanka@example.test",
        passwordHash = "irrelevant",
        roles = roles,
        createdAt = clock.instant(),
    )

    private fun properties() = JwtProperties(
        secret = "test-secret-at-least-thirty-two-bytes-long",
        issuer = "barometr",
        audience = "barometr-web",
        accessTtl = Duration.ofMinutes(15),
        refreshTtl = Duration.ofDays(30),
        refreshGrace = Duration.ofSeconds(15),
    )
}
