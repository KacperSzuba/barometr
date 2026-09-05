package pl.barometr.identity.internal.apikey

import pl.barometr.identity.api.ApiScope
import pl.barometr.identity.api.ApiTier
import java.time.Instant
import java.util.UUID

/**
 * A key as its owner sees it in a list — everything except the key.
 *
 * [requests] is what makes "which of my keys is the script that has been hammering this"
 * answerable by the person who made them, rather than only by whoever reads the metrics.
 */
data class IssuedApiKey(
    val id: UUID,
    val owner: UUID,
    val name: String,
    val tier: ApiTier,
    val scopes: Set<ApiScope>,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
    val lastUsedAt: Instant?,
    val requests: Long,
)
