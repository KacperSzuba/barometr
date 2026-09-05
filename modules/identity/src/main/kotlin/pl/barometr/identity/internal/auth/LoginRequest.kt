package pl.barometr.identity.internal.auth

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * An address, a password, and — when this browser has been here before — the token that
 * says its second factor was answered within the last month.
 *
 * The device token is optional and never required: an account with no second factor
 * ignores it, and one that has a factor and no token is simply asked for a code.
 */
data class LoginRequest(
    @field:NotBlank val email: String,
    @field:NotBlank val password: String,
    /** From a previous sign-in that asked to be remembered; see [TokenPairResponse]. */
    @field:Size(max = MAX_TOKEN_LENGTH)
    val deviceToken: String? = null,
) {
    private companion object {
        /** Thirty-two bytes of url-safe base64 is forty-three characters; nothing valid is longer. */
        const val MAX_TOKEN_LENGTH = 64
    }
}
