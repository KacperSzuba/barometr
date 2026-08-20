package pl.barometr.identity.internal.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

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
