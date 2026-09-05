package pl.barometr.identity.internal.twofactor

import java.time.Instant
import java.util.UUID

/**
 * [TrustedDevices] in a map.
 *
 * Expiry is applied on read, exactly as the real one applies it in the `WHERE` clause:
 * a token that has run out must not be found at all, so that no path above can forget
 * to look.
 */
class InMemoryTrustedDevices : TrustedDevices {
    private val stored = mutableMapOf<UUID, RememberedDevice>()

    override fun remember(device: RememberedDevice): RememberedDevice {
        stored[device.id] = device
        return device
    }

    override fun byTokenHash(hash: String, now: Instant): RememberedDevice? =
        stored.values.firstOrNull { it.tokenHash == hash && it.revokedAt == null && it.expiresAt.isAfter(now) }

    override fun markUsed(id: UUID, at: Instant) {
        stored[id]?.let { stored[id] = it.copy(lastUsedAt = at) }
    }

    override fun liveFor(userId: UUID, now: Instant): List<RememberedDevice> =
        stored.values
            .filter { it.userId == userId && it.revokedAt == null && it.expiresAt.isAfter(now) }
            .sortedByDescending { it.createdAt }

    override fun revoke(userId: UUID, id: UUID, at: Instant): Boolean {
        val live = stored[id]?.takeIf { it.userId == userId && it.revokedAt == null } ?: return false
        stored[id] = live.copy(revokedAt = at)
        return true
    }

    override fun revokeAll(userId: UUID, at: Instant): Int =
        stored.values
            .filter { it.userId == userId && it.revokedAt == null }
            .onEach { stored[it.id] = it.copy(revokedAt = at) }
            .size
}
