package pl.barometr.identity.internal.twofactor

import java.time.Instant
import java.util.UUID

/**
 * [TwoFactorSecrets] in a map, holding the secret as the service passes it.
 *
 * Encryption is the adapter's business and is tested against the database; what the
 * suites using this fake are about is the policy above it — set up, confirm, disable —
 * which is the same whatever the row looks like.
 */
class InMemoryTwoFactorSecrets : TwoFactorSecrets {
    private val stored = mutableMapOf<UUID, EnrolledSecret>()

    override fun save(secret: EnrolledSecret) {
        stored[secret.userId] = secret
    }

    override fun forUser(userId: UUID): EnrolledSecret? = stored[userId]

    override fun confirm(userId: UUID, at: Instant): Boolean {
        val unconfirmed = stored[userId]?.takeIf { !it.isConfirmed } ?: return false
        stored[userId] = unconfirmed.copy(confirmedAt = at)
        return true
    }

    override fun delete(userId: UUID): Boolean = stored.remove(userId) != null
}
