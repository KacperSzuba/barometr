package pl.barometr.identity.api

import java.time.Instant

/**
 * Events identity publishes. Consumers annotate a handler with
 * `@ApplicationModuleListener`, which makes delivery asynchronous and
 * transactional — Spring Modulith writes each publication to `event_publication`
 * and retries it, so this doubles as the outbox.
 */
data class UserRegistered(
    val userId: UserId,
    val email: String,
    val occurredAt: Instant,
)

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
