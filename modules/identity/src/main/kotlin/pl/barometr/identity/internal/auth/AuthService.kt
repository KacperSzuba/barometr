package pl.barometr.identity.internal.auth

import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.UserRegistered
import pl.barometr.identity.api.UserSessionsRevoked
import pl.barometr.identity.internal.twofactor.DeviceTrust
import pl.barometr.identity.internal.twofactor.TwoFactorSignIn
import pl.barometr.identity.internal.workspace.WorkspacePolicies
import pl.barometr.identity.internal.user.User
import pl.barometr.identity.internal.user.Users
import pl.barometr.shared.Ids
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class AuthService(
    private val users: Users,
    private val refreshTokens: RefreshTokenService,
    private val sessions: SignedInSessions,
    private val tokens: TokenService,
    private val twoFactor: TwoFactorSignIn,
    private val deviceTrust: DeviceTrust,
    private val policies: WorkspacePolicies,
    private val passwordEncoder: PasswordEncoder,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {

    @Transactional
    fun register(request: RegisterRequest, from: ClientFingerprint = ClientFingerprint.UNKNOWN): TokenPairResponse {
        val email = normaliseEmail(request.email)
        if (users.existsWithEmail(email)) throw EmailAlreadyUsedException()

        val user = users.add(
            User(
                id = Ids.next(),
                email = email,
                passwordHash = hashPassword(request.password),
                createdAt = clock.instant(),
            ),
        )

        // Published rather than called: onboarding, audit and analytics all care,
        // and none of them should be a compile-time dependency of registration.
        events.publishEvent(UserRegistered(UserId(user.id), user.email, clock.instant()))

        return issuePair(user, from)
    }

    @Transactional
    /**
     * A password, and then — for an account that has one — a second factor.
     *
     * The challenge is opened only after the password has been checked, so nothing about
     * the response says whether an address has a second factor until whoever is asking
     * has proved they know the password for it.
     */
    fun login(request: LoginRequest, from: ClientFingerprint = ClientFingerprint.UNKNOWN): LoginOutcome {
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
        if (!twoFactor.isRequiredFor(user.id)) return issuePair(user, from)

        // A device that answered the factor within the last month may skip it. What that
        // costs is stated where the trust is granted; what it buys is a factor people
        // leave switched on.
        if (deviceTrust.trusts(UserId(user.id), request.deviceToken)) return issuePair(user, from)

        val challenge = twoFactor.challengeFor(user.id)

        return TwoFactorRequiredResponse(
            challengeId = challenge.id,
            expiresIn = Duration.between(clock.instant(), challenge.expiresAt).seconds,
        )
    }

    /**
     * The second half of a sign-in: the challenge answered, and the tokens it was
     * standing in for.
     *
     * Not `@Transactional`, for the reason [refresh] is not — the attempt counter is
     * kept by a call that suppresses its own rollback, and an outer boundary here would
     * apply its own rules and undo it.
     */
    fun completeSecondFactor(
        challengeId: UUID,
        code: String,
        rememberDevice: Boolean = false,
        from: ClientFingerprint = ClientFingerprint.UNKNOWN,
    ): TokenPairResponse {
        val userId = twoFactor.answerChallenge(challengeId, code)
        val user = users.byId(userId)?.takeIf { it.enabled } ?: throw InvalidCredentialsException()

        val pair = issuePair(user, from)

        return when {
            rememberDevice -> pair.copy(deviceToken = deviceTrust.rememberDevice(UserId(user.id), from.userAgent))
            else -> pair
        }
    }

    /**
     * Intentionally not `@Transactional`.
     *
     * `rotate` suppresses rollback so that revoking a stolen token family
     * survives the exception it throws. An outer transaction here would undo
     * exactly that, because the outer boundary applies its own rollback rules.
     */
    fun refresh(
        rawRefreshToken: String,
        from: ClientFingerprint = ClientFingerprint.UNKNOWN,
    ): TokenPairResponse {
        val rotation = refreshTokens.rotate(rawRefreshToken, from)
        val user = users.byId(rotation.userId) ?: throw InvalidRefreshTokenException()
        if (!user.enabled) throw InvalidRefreshTokenException()

        // Recomputed on every refresh rather than carried from the sign-in: somebody who
        // has just enrolled stops being told to enrol within one token's lifetime, and
        // somebody whose workspace has just turned the policy on starts being told.
        val access = tokens.createAccessToken(user, rotation.refreshToken.familyId, mustEnrol(user))
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

    /**
     * A new login: a family of refresh tokens, a session row naming the device it was
     * made from, and an access token that says which session it belongs to.
     */
    private fun issuePair(user: User, from: ClientFingerprint): TokenPairResponse {
        val refresh = refreshTokens.issue(user.id)
        sessions.openSession(user.id, refresh.familyId, from)
        val access = tokens.createAccessToken(user, refresh.familyId, mustEnrol(user))

        return TokenPairResponse(
            accessToken = access.value,
            refreshToken = refresh.raw,
            expiresIn = access.expiresInSeconds,
        )
    }

    /**
     * Whether this account is being let in only to set a second factor up.
     *
     * A workspace that has just turned the policy on has members who have not enrolled,
     * and refusing them outright would leave them with no way to comply — including the
     * administrator who turned it on. So they are signed in, and the claim is what stops
     * the token reaching anything but the enrolment routes.
     */
    private fun mustEnrol(user: User): Boolean =
        policies.requiresTwoFactor(UserId(user.id)) && !twoFactor.isRequiredFor(user.id)

    private fun normaliseEmail(email: String) = email.trim().lowercase()

    /** `PasswordEncoder.encode` is declared nullable; BCrypt never returns null. */
    private fun hashPassword(raw: String): String =
        requireNotNull(passwordEncoder.encode(raw)) { "Password encoder returned no hash" }
}
