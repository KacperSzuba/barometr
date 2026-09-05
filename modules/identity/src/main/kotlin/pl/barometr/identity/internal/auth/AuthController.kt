package pl.barometr.identity.internal.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Signing in, and staying signed in.
 *
 * Every route here takes the request itself, for one reason: a login records the device
 * it was made from, so the account's session list can say "Warsaw, an hour ago, a
 * browser you have used before". Nothing else in this controller reads the request, and
 * nothing decides anything from it — see [ClientFingerprint].
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: RegisterRequest, http: HttpServletRequest): TokenPairResponse =
        authService.register(request, ClientFingerprint.of(http))

    /**
     * Two answers, two status codes: `200` with a pair of tokens, or `202` with a
     * challenge for the account's second factor.
     *
     * The status is part of the answer on purpose. A client that reads only the body
     * gets a type it cannot mistake for tokens; one that reads only the status knows it
     * is not finished. Neither can end up believing it has signed somebody in.
     */
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        http: HttpServletRequest,
    ): ResponseEntity<LoginOutcome> = when (val outcome = authService.login(request, ClientFingerprint.of(http))) {
        is TokenPairResponse -> ResponseEntity.ok(outcome)
        is TwoFactorRequiredResponse -> ResponseEntity.status(HttpStatus.ACCEPTED).body(outcome)
    }

    @PostMapping("/login/2fa")
    fun secondFactor(
        @Valid @RequestBody request: SecondFactorRequest,
        http: HttpServletRequest,
    ): TokenPairResponse =
        authService.completeSecondFactor(
            challengeId = request.challengeId,
            code = request.code,
            rememberDevice = request.rememberDevice,
            from = ClientFingerprint.of(http),
        )

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest, http: HttpServletRequest): TokenPairResponse =
        authService.refresh(request.refreshToken, ClientFingerprint.of(http))

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@Valid @RequestBody request: RefreshRequest) =
        authService.logout(request.refreshToken)
}
