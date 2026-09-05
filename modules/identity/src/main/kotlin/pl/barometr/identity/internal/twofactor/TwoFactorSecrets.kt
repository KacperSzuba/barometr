package pl.barometr.identity.internal.twofactor

import java.time.Instant
import java.util.UUID

/** Where the shared secrets live. Encryption is storage's business, not the service's. */
interface TwoFactorSecrets {

    fun save(secret: EnrolledSecret)

    fun forUser(userId: UUID): EnrolledSecret?

    /** @return false when there was nothing unconfirmed to confirm. */
    fun confirm(userId: UUID, at: Instant): Boolean

    fun delete(userId: UUID): Boolean
}
