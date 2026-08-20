package pl.barometr.identity.internal.auth

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.UserRegistered
import pl.barometr.identity.api.UserSessionsRevoked
import pl.barometr.identity.internal.config.JwtConfig
import pl.barometr.identity.internal.config.JwtProperties
import pl.barometr.identity.internal.user.InMemoryRefreshTokens
import pl.barometr.identity.internal.user.InMemoryUsers
import pl.barometr.identity.internal.user.User
import pl.barometr.shared.Ids
import pl.barometr.testing.TestClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Registration, sign-in and sign-out.
 *
 * Built the way Spring builds it — real password encoder, real token service, fakes
 * only for storage — so what is under test is the policy rather than a stack of
 * stubs agreeing with each other.
 */
class AuthServiceTest {

    private val clock = TestClock()
    private val users = InMemoryUsers()
    private val tokens = InMemoryRefreshTokens()
    private val events = RecordingEvents()

    // Cost 4 rather than the production 12: these tests hash dozens of passwords and
    // the work factor proves nothing here that the configuration does not state.
    private val passwords = BCryptPasswordEncoder(4)

    private lateinit var service: AuthService

    @BeforeEach
    fun setUp() {
        val properties = properties()
        service = AuthService(
            users = users,
            refreshTokens = RefreshTokenService(tokens, properties, clock),
            tokens = TokenService(JwtConfig(properties).jwtEncoder(), properties, clock),
            passwordEncoder = passwords,
            events = events,
            clock = clock,
        )
    }

    @Test
    fun `registration stores the user and announces it`() {
        val pair = service.register(RegisterRequest("Poslanka@Example.test", "correct-horse"))

        val stored = assertNotNull(users.byEmail("poslanka@example.test"))
        assertTrue(passwords.matches("correct-horse", stored.passwordHash))
        assertEquals(setOf(User.DEFAULT_ROLE), stored.roles)
        assertTrue(pair.accessToken.isNotBlank())
        assertTrue(pair.refreshToken.isNotBlank())

        val announced = events.of<UserRegistered>().single()
        assertEquals(UserId(stored.id), announced.userId)
        assertEquals(clock.instant(), announced.occurredAt)
    }

    /** The e-mail is the account's identity, so its spelling must not create two. */
    @Test
    fun `an address differing only in case or spacing is the same account`() {
        service.register(RegisterRequest("poslanka@example.test", "correct-horse"))

        assertFailsWith<EmailAlreadyUsedException> {
            service.register(RegisterRequest("  Poslanka@EXAMPLE.test ", "another-password"))
        }
    }

    @Test
    fun `signing in with the right password issues a pair`() {
        service.register(RegisterRequest("poslanka@example.test", "correct-horse"))

        val pair = service.login(LoginRequest("poslanka@example.test", "correct-horse"))

        assertTrue(pair.accessToken.isNotBlank())
        assertEquals(properties().accessTtl.seconds, pair.expiresIn)
    }

    /**
     * The two failures a caller can tell apart are the two an attacker uses to
     * enumerate accounts, so they are deliberately one failure with one code.
     */
    @Test
    fun `a wrong password and an unknown address fail identically`() {
        service.register(RegisterRequest("poslanka@example.test", "correct-horse"))

        val wrongPassword = assertFailsWith<InvalidCredentialsException> {
            service.login(LoginRequest("poslanka@example.test", "not-the-password"))
        }
        val unknownAddress = assertFailsWith<InvalidCredentialsException> {
            service.login(LoginRequest("nobody@example.test", "correct-horse"))
        }

        assertEquals(wrongPassword.code, unknownAddress.code)
        assertEquals("invalid_credentials", wrongPassword.code)
    }

    @Test
    fun `a disabled account cannot sign in`() {
        service.register(RegisterRequest("poslanka@example.test", "correct-horse"))
        users.disable("poslanka@example.test")

        assertFailsWith<InvalidCredentialsException> {
            service.login(LoginRequest("poslanka@example.test", "correct-horse"))
        }
    }

    @Test
    fun `refreshing exchanges the token for a new pair`() {
        val first = service.register(RegisterRequest("poslanka@example.test", "correct-horse"))

        val second = service.refresh(first.refreshToken)

        assertNotEquals(first.refreshToken, second.refreshToken)
        assertTrue(second.accessToken.isNotBlank())
    }

    @Test
    fun `a disabled account cannot refresh its way back in`() {
        val pair = service.register(RegisterRequest("poslanka@example.test", "correct-horse"))
        users.disable("poslanka@example.test")

        assertFailsWith<InvalidRefreshTokenException> { service.refresh(pair.refreshToken) }
    }

    @Test
    fun `signing out revokes the session and says why`() {
        val pair = service.register(RegisterRequest("poslanka@example.test", "correct-horse"))

        service.logout(pair.refreshToken)

        assertTrue(tokens.live().isEmpty())
        val revoked = events.of<UserSessionsRevoked>().single()
        assertEquals(UserSessionsRevoked.RevocationReason.LOGOUT, revoked.reason)
    }

    @Test
    fun `signing out with a token nobody issued announces nothing`() {
        service.logout("never-issued")

        assertTrue(events.of<UserSessionsRevoked>().isEmpty())
    }

    @Test
    fun `the password is never stored as given`() {
        service.register(RegisterRequest("poslanka@example.test", "correct-horse"))

        val stored = assertNotNull(users.byEmail("poslanka@example.test"))
        assertNotEquals("correct-horse", stored.passwordHash)
        assertTrue(stored.passwordHash.startsWith("\$2"), "a BCrypt hash")
    }

    private fun properties() = JwtProperties(
        secret = "test-secret-at-least-thirty-two-bytes-long",
        issuer = "barometr",
        audience = "barometr-web",
        accessTtl = Duration.ofMinutes(15),
        refreshTtl = Duration.ofDays(30),
        refreshGrace = Duration.ofSeconds(15),
    )

    private class RecordingEvents : ApplicationEventPublisher {
        private val published = mutableListOf<Any>()

        override fun publishEvent(event: ApplicationEvent) = publishEvent(event as Any)

        override fun publishEvent(event: Any) {
            published += event
        }

        inline fun <reified T> of(): List<T> = published.filterIsInstance<T>()
    }
}
