package pl.barometr.identity.internal.profile

import java.util.UUID

data class UserResponse(
    val id: UUID,
    val email: String,
    val roles: List<String>,
)
