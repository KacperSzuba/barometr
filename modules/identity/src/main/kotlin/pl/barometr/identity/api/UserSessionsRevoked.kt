package pl.barometr.identity.api

import java.time.Instant

data class UserSessionsRevoked(
    val userId: UserId,
    val reason: RevocationReason,
    val occurredAt: Instant,
) {
    enum class RevocationReason {
        LOGOUT,

        /** A refresh token was replayed outside the race window — assume theft. */
        TOKEN_REUSE_DETECTED,
    }
}
