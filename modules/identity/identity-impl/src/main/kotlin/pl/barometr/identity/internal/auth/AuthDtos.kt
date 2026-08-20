package pl.barometr.identity.internal.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class RegisterRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 320)
    val email: String,
    // BCrypt silently ignores anything past 72 bytes, so the upper bound is the
    // algorithm's rather than a policy choice.
    @field:NotBlank
    @field:Size(min = 8, max = 72)
    val password: String,
)

data class LoginRequest(
    @field:NotBlank val email: String,
    @field:NotBlank val password: String,
)

data class RefreshRequest(
    @field:NotBlank val refreshToken: String,
)

/**
 * Both tokens travel in the response body — this service sets no cookies.
 * Next.js receives them server-side and puts them into HttpOnly cookies on its
 * own domain, so nothing here assumes a browser is on the other end.
 */
data class TokenPairResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

data class UserResponse(
    val id: UUID,
    val email: String,
    val roles: List<String>,
)
