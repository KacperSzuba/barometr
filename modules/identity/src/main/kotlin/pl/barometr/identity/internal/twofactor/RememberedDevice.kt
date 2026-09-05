package pl.barometr.identity.internal.twofactor

import java.time.Instant
import java.util.UUID

/**
 * A device that has answered the second factor and may skip it until [expiresAt].
 *
 * The token itself is not here: only its hash is stored, and the plaintext exists for
 * exactly as long as it takes to hand it to the client that asked to be remembered.
 */
data class RememberedDevice(
    val id: UUID,
    val userId: UUID,
    val tokenHash: String,
    val userAgent: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val lastUsedAt: Instant?,
    val revokedAt: Instant?,
)
