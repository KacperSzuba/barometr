package pl.barometr.identity.internal.auth

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEvent
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.UserRegistered
import pl.barometr.identity.api.UserSessionsRevoked
import pl.barometr.identity.internal.config.JwtConfig
import pl.barometr.identity.internal.config.JwtProperties
import pl.barometr.identity.internal.twofactor.DeviceTrust
import pl.barometr.identity.api.JwtClaims
import tools.jackson.databind.json.JsonMapper
import java.util.Base64
import pl.barometr.identity.internal.twofactor.AuthenticatorApp
import pl.barometr.identity.internal.twofactor.InMemoryLoginChallenges
import pl.barometr.identity.internal.twofactor.InMemoryTrustedDevices
import pl.barometr.identity.internal.twofactor.InMemoryRecoveryCodes
import pl.barometr.identity.internal.twofactor.InMemoryTwoFactorSecrets
import pl.barometr.identity.internal.twofactor.TotpCodes
import pl.barometr.identity.internal.twofactor.TwoFactorEnrolment
import pl.barometr.identity.internal.twofactor.TwoFactorProperties
import pl.barometr.identity.internal.twofactor.TwoFactorSignIn
import pl.barometr.identity.internal.user.InMemoryRefreshTokens
import pl.barometr.identity.internal.user.InMemorySessions
import pl.barometr.identity.internal.user.UnknownLocations
import pl.barometr.identity.internal.workspace.InMemoryWorkspaceInvitations
import pl.barometr.identity.internal.workspace.InMemoryWorkspaces
import pl.barometr.identity.internal.workspace.TeamWorkspaces
import pl.barometr.identity.internal.workspace.WorkspaceProperties
import pl.barometr.identity.internal.workspace.WorkspacePolicies
import pl.barometr.identity.internal.user.InMemoryUsers
import pl.barometr.identity.internal.user.User
import pl.barometr.shared.Ids
import pl.barometr.testing.TestClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    private val sessions = InMemorySessions()
    private val workspaces = InMemoryWorkspaces()
    private val policies = WorkspacePolicies(workspaces)
    private val events = RecordingEvents()

    private val secrets = InMemoryTwoFactorSecrets()
    private val recoveryCodes = InMemoryRecoveryCodes()
    private val challenges = InMemoryLoginChallenges()
    private val codes = TotpCodes(clock)
    private val trustedDevices = InMemoryTrustedDevices()
    private val twoFactorProperties = TwoFactorProperties(encryptionKey = "k", encryptionSalt = "5c0744940b5c369b")
    private val trust = DeviceTrust(trustedDevices, twoFactorProperties, clock)
    private val enrolment = TwoFactorEnrolment(secrets, recoveryCodes, trust, codes, twoFactorProperties, clock)

    // Cost 4 rather than the production 12: these tests hash dozens of passwords and
    // the work factor proves nothing here that the configuration does not state.
    private val passwords = BCryptPasswordEncoder(4)

    private lateinit var service: AuthService

    @BeforeEach
    fun setUp() {
        val properties = properties()
        service = AuthService(
            users = users,
            refreshTokens = RefreshTokenService(tokens, sessions, policies, properties, SessionProperties(), clock),
            sessions = SignedInSessions(sessions, UnknownLocations, tokens, events, clock),
            deviceTrust = trust,
            policies = policies,
            twoFactor = TwoFactorSignIn(secrets, recoveryCodes, challenges, codes, enrolment, twoFactorProperties, clock),
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

    /**
     * What a workspace policy does to a sign-in: nothing, except mark the token.
     *
     * Refusing outright would leave somebody with no way to comply — including the
     * administrator who has just turned the policy on — so the token says `enrol` and the
     * application's filter chain is what makes that mean something.
     */
    @Test
    fun `a workspace that insists on a second factor marks the tokens of whoever has not set one up`() {
        val account = service.register(RegisterRequest("poslanka@example.test", "correct-horse"))
        val user = assertNotNull(users.byEmail("poslanka@example.test"))
        requireTwoFactorFor(UserId(user.id))

        val signedIn = assertIs<TokenPairResponse>(service.login(LoginRequest("poslanka@example.test", "correct-horse")))

        assertEquals(false, enrolmentRequiredIn(account.accessToken), "the policy did not exist at registration")
        assertEquals(true, enrolmentRequiredIn(signedIn.accessToken))
    }

    @Test
    fun `once a second factor exists the mark is gone from the next token`() {
        service.register(RegisterRequest("poslanka@example.test", "correct-horse"))
        val user = assertNotNull(users.byEmail("poslanka@example.test"))
        requireTwoFactorFor(UserId(user.id))
        enrolTwoFactorFor(UserId(user.id))

        val signedIn = assertIs<TwoFactorRequiredResponse>(
            service.login(LoginRequest("poslanka@example.test", "correct-horse")),
        )
        val pair = service.completeSecondFactor(signedIn.challengeId, currentCodeFor(UserId(user.id)))

        assertEquals(false, enrolmentRequiredIn(pair.accessToken))
    }

    @Test
    fun `an account in no workspace is asked for nothing`() {
        val account = service.register(RegisterRequest("poslanka@example.test", "correct-horse"))

        assertEquals(false, enrolmentRequiredIn(account.accessToken))
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

        val pair = assertIs<TokenPairResponse>(service.login(LoginRequest("poslanka@example.test", "correct-horse")))

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

    /** A workspace that insists on a second factor, with this account in it. */
    private fun requireTwoFactorFor(user: UserId) {
        val workspace = TeamWorkspaces(workspaces, InMemoryWorkspaceInvitations(), WorkspaceProperties(), clock)
            .createWorkspace(user, "Kancelaria Nowak")

        workspaces.updatePolicy(workspace.id, requireTwoFactor = true, idleTimeout = null)
    }

    private fun enrolTwoFactorFor(user: UserId) {
        enrolment.beginEnrolment(UserId(user.value), "poslanka@example.test")
        enrolment.confirmEnrolment(UserId(user.value), currentCodeFor(user))
    }

    private fun currentCodeFor(user: UserId): String =
        AuthenticatorApp.codeFor(checkNotNull(secrets.forUser(user.value)).secret, clock.instant())

    /**
     * The claim read out of the token's payload rather than through a decoder.
     *
     * This suite's clock is fixed in the past, and Nimbus validates `exp` against the
     * system clock — which no injected clock reaches. What is being checked here is what
     * identity put in the token; that a real decoder can read one is
     * [TokenServiceTest]'s question, and it asks it against the real decoder.
     */
    private fun enrolmentRequiredIn(accessToken: String): Boolean {
        val payload = String(Base64.getUrlDecoder().decode(accessToken.split(".")[1]), Charsets.UTF_8)

        return JsonMapper.builder().build().readTree(payload).get(JwtClaims.ENROLMENT_REQUIRED).asBoolean()
    }

}
