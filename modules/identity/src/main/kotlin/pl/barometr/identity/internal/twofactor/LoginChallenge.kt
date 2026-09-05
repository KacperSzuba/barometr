package pl.barometr.identity.internal.twofactor

import java.time.Instant
import java.util.UUID

/**
 * A password that has been proved and has not yet bought anything.
 *
 * It exists because the two factors arrive in two requests: something has to remember,
 * between them, that the first one was right — and it must not be a token that grants
 * anything, or the second factor would be optional in practice.
 */
data class LoginChallenge(
    val id: UUID,
    val userId: UUID,
    val expiresAt: Instant,
    val consumedAt: Instant?,
    val attempts: Int,
    val createdAt: Instant,
)
