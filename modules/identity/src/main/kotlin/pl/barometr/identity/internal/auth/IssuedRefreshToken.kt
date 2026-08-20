package pl.barometr.identity.internal.auth

import java.time.Instant
import java.util.UUID

data class IssuedRefreshToken(
    val raw: String,
    val id: UUID,
    val familyId: UUID,
    val expiresAt: Instant,
)
