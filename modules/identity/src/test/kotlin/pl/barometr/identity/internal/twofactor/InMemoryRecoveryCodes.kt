package pl.barometr.identity.internal.twofactor

import java.time.Instant
import java.util.UUID

/** [RecoveryCodes] in a map: hashes, and when each was spent. */
class InMemoryRecoveryCodes : RecoveryCodes {
    private val stored = mutableMapOf<UUID, MutableMap<String, Instant?>>()

    override fun replaceAll(userId: UUID, hashes: List<String>, at: Instant) {
        stored[userId] = hashes.associateWith { null as Instant? }.toMutableMap()
    }

    override fun consume(userId: UUID, hash: String, at: Instant): Boolean {
        val codes = stored[userId] ?: return false
        if (!codes.containsKey(hash) || codes[hash] != null) return false
        codes[hash] = at
        return true
    }

    override fun unusedCount(userId: UUID): Int = stored[userId]?.count { it.value == null } ?: 0

    override fun deleteAll(userId: UUID) {
        stored.remove(userId)
    }
}
