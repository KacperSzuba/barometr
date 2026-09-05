package pl.barometr.identity.internal.twofactor

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.barometr.identity.api.UserLookup
import pl.barometr.identity.api.callerOf
import java.security.Principal
import java.util.UUID

/**
 * Turning a second factor on, checking where it stands, and turning it off.
 *
 * Every route here is about the caller's own account: a second factor is not something
 * one person configures for another, and the one case where somebody else has to
 * intervene — a lost phone and no recovery codes — is an operator's route with an audit
 * entry behind it, not a parameter here.
 */
@RestController
@RequestMapping("/api/v1/auth/2fa")
class TwoFactorController(
    private val enrolment: TwoFactorEnrolment,
    private val trust: DeviceTrust,
    private val users: UserLookup,
) {

    @GetMapping
    fun status(caller: Principal): TwoFactorStatus = enrolment.statusOf(callerOf(caller))

    /**
     * Starts enrolment and hands back the secret. Nothing about signing in changes until
     * a code from it comes back to [confirm].
     */
    @PostMapping
    fun begin(caller: Principal): SetupResponse {
        val user = callerOf(caller)
        val email = users.findById(user)?.email ?: throw UnknownAccountException()
        val setup = enrolment.beginEnrolment(user, email)

        return SetupResponse(setup.secret, setup.setupUri)
    }

    /**
     * Confirms the authenticator works, turns the factor on, and returns the recovery
     * codes — the only time they are readable anywhere.
     */
    @PostMapping("/confirmation")
    fun confirm(caller: Principal, @Valid @RequestBody request: CodeRequest): RecoveryCodesResponse =
        RecoveryCodesResponse(enrolment.confirmEnrolment(callerOf(caller), request.code))

    /**
     * A fresh set of recovery codes, for somebody who has used most of theirs. The old
     * set stops working, which is the point.
     */
    @PostMapping("/recovery-codes")
    fun mintRecoveryCodes(caller: Principal): RecoveryCodesResponse =
        RecoveryCodesResponse(enrolment.mintRecoveryCodes(callerOf(caller)))

    /**
     * Turns it off, with a current code as proof.
     *
     * The caller is already signed in, so this is not about identity — it is about
     * somebody who walked away from an unlocked laptop. Whoever is at the keyboard has
     * to still hold the second factor to remove it.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun disable(caller: Principal, @Valid @RequestBody request: CodeRequest) {
        val user = callerOf(caller)
        enrolment.confirmDisable(user, request.code)
    }

    /**
     * The devices allowed to sign in with the password alone, and how long each has
     * left.
     *
     * Worth a route of its own rather than a line in the session list: a session is a
     * login that has happened, and this is permission to make the next one with one
     * factor. Somebody deciding whether to end one is asking a different question.
     */
    @GetMapping("/trusted-devices")
    fun trustedDevices(caller: Principal): List<TrustedDeviceResponse> =
        trust.devicesTrustedBy(callerOf(caller)).map {
            TrustedDeviceResponse(
                id = it.id,
                userAgent = it.userAgent,
                trustedAt = it.createdAt.toString(),
                expiresAt = it.expiresAt.toString(),
                lastUsedAt = it.lastUsedAt?.toString(),
            )
        }

    @DeleteMapping("/trusted-devices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun forgetDevice(caller: Principal, @PathVariable id: UUID) {
        trust.forgetDevice(callerOf(caller), id)
    }

    /** "Ask for a code everywhere again" — the button somebody presses after losing a laptop. */
    @DeleteMapping("/trusted-devices")
    fun forgetEveryDevice(caller: Principal): ForgottenResponse =
        ForgottenResponse(trust.forgetEveryDevice(callerOf(caller)))

    data class CodeRequest(@field:NotBlank val code: String)

    data class TrustedDeviceResponse(
        val id: UUID,
        val userAgent: String?,
        val trustedAt: String,
        val expiresAt: String,
        val lastUsedAt: String?,
    )

    data class ForgottenResponse(val forgotten: Int)

    data class SetupResponse(
        /** For somebody typing it in by hand, when a camera is not an option. */
        val secret: String,
        /** What a QR image is drawn from; drawing it is the client's job. */
        val setupUri: String,
    )

    data class RecoveryCodesResponse(
        /** Shown once. Only their hashes are kept. */
        val recoveryCodes: List<String>,
    )
}
