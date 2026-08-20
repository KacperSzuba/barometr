package pl.barometr.identity.internal.auth

import java.util.UUID

data class RotationResult(val userId: UUID, val refreshToken: IssuedRefreshToken)
