package pl.barometr.identity.internal.auth

import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.UserRegistered
import pl.barometr.identity.api.UserSessionsRevoked
import pl.barometr.identity.internal.user.UserEntity
import pl.barometr.identity.internal.user.Users
import pl.barometr.shared.Ids
import java.time.Clock
import java.time.Instant

@Service
class AuthService(
    private val users: Users,
    private val refreshTokens: RefreshTokenService,
    private val tokens: TokenService,
    private val passwordEncoder: PasswordEncoder,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {

    @Transactional
    fun register(request: RegisterRequest): TokenPairResponse {
        val email = normaliseEmail(request.email)
        if (users.existsWithEmail(email)) throw EmailAlreadyUsedException()

        val user = users.add(
            UserEntity(
                id = Ids.next(),
                email = email,
                passwordHash = hashPassword(request.password),
                createdAt = clock.instant(),
            ),
        )

        // Published rather than called: onboarding, audit and analytics all care,
        // and none of them should be a compile-time dependency of registration.
        events.publishEvent(UserRegistered(UserId(user.id), user.email, clock.instant()))

        return issuePair(user)
    }

    @Transactional
    fun login(request: LoginRequest): TokenPairResponse {
        val user = users.byEmail(normaliseEmail(request.email))

        if (user == null) {
            // Hash anyway: returning early would make an unknown e-mail measurably
            // faster than a wrong password and turn response time into an oracle.
            passwordEncoder.encode(request.password)
            throw InvalidCredentialsException()
        }
        if (!user.enabled || !passwordEncoder.matches(request.password, user.passwordHash)) {
            throw InvalidCredentialsException()
        }
        return issuePair(user)
    }

    /**
     * Intentionally not `@Transactional`.
     *
     * `rotate` suppresses rollback so that revoking a stolen token family
     * survives the exception it throws. An outer transaction here would undo
     * exactly that, because the outer boundary applies its own rollback rules.
     */
    fun refresh(rawRefreshToken: String): TokenPairResponse {
        val rotation = refreshTokens.rotate(rawRefreshToken)
        val user = users.byId(rotation.userId) ?: throw InvalidRefreshTokenException()
        if (!user.enabled) throw InvalidRefreshTokenException()

        val access = tokens.createAccessToken(user)
        return TokenPairResponse(
            accessToken = access.value,
            refreshToken = rotation.refreshToken.raw,
            expiresIn = access.expiresInSeconds,
        )
    }

    fun logout(rawRefreshToken: String) {
        val userId = refreshTokens.revokeFamilyOf(rawRefreshToken) ?: return
        events.publishEvent(
            UserSessionsRevoked(
                userId = UserId(userId),
                reason = UserSessionsRevoked.RevocationReason.LOGOUT,
                occurredAt = clock.instant(),
            ),
        )
    }

    private fun issuePair(user: UserEntity): TokenPairResponse {
        val access = tokens.createAccessToken(user)
        val refresh = refreshTokens.issue(user.id)
        return TokenPairResponse(
            accessToken = access.value,
            refreshToken = refresh.raw,
            expiresIn = access.expiresInSeconds,
        )
    }

    private fun normaliseEmail(email: String) = email.trim().lowercase()

    /** `PasswordEncoder.encode` is declared nullable; BCrypt never returns null. */
    private fun hashPassword(raw: String): String =
        requireNotNull(passwordEncoder.encode(raw)) { "Password encoder returned no hash" }
}
