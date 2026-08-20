package pl.barometr.identity.internal.auth

import org.springframework.security.oauth2.jwt.JwtValidationException
import org.junit.jupiter.api.Test
import pl.barometr.identity.internal.config.JwtClaims
import pl.barometr.identity.internal.config.JwtConfig
import pl.barometr.identity.internal.config.JwtProperties
import pl.barometr.identity.internal.user.UserEntity
import pl.barometr.shared.Ids
import pl.barometr.testing.TestClock
import java.time.Duration
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

    // Real time, not a fixed instant: Nimbus validates `exp` against the system
    // clock, which no test can hand it. What the injected clock controls here is
    // when the token claims to have been issued.
    private val clock = TestClock(Instant.now())
    private val properties = properties()
    private val config = JwtConfig(properties)
    private val service = TokenService(config.jwtEncoder(), properties, clock)

    @Test
    fun `the access token carries the claims the resource server checks`() {
        val user = user(roles = "USER,OPERATOR")

        val token = service.createAccessToken(user)
        val decoded = config.jwtDecoder().decode(token.value)

        assertEquals(user.id.toString(), decoded.subject)
        // Read as a claim, not through `Jwt.getIssuer()`: this issuer is a bare
        // name rather than a URL, and that accessor insists on converting it to one.
        assertEquals("barometr", decoded.getClaimAsString("iss"))
        assertEquals(listOf("barometr-web"), decoded.audience)
        assertEquals(user.email, decoded.getClaimAsString(JwtClaims.EMAIL))
        assertEquals(listOf("USER", "OPERATOR"), decoded.getClaimAsStringList(JwtClaims.ROLES))
    }

    @Test
    fun `the token expires after the configured lifetime`() {
        val token = service.createAccessToken(user())
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
            .createAccessToken(user())

        val failure = assertFailsWith<JwtValidationException> {
            config.jwtDecoder().decode(foreign.value)
        }
        assertTrue(failure.message!!.contains("audience"), failure.message!!)
    }

    @Test
    fun `a token signed with another secret is rejected`() {
        val foreignKey = properties.copy(secret = "a-completely-different-secret-of-enough-length")
        val foreign = TokenService(JwtConfig(foreignKey).jwtEncoder(), foreignKey, clock)
            .createAccessToken(user())

        assertFailsWith<org.springframework.security.oauth2.jwt.BadJwtException> {
            config.jwtDecoder().decode(foreign.value)
        }
    }

    private fun user(roles: String = "USER") = UserEntity(
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
