package pl.barometr.identity.internal.auth

import jakarta.validation.constraints.NotBlank

data class RefreshRequest(
    @field:NotBlank val refreshToken: String,
)
