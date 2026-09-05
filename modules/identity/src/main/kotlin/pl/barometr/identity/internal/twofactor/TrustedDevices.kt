package pl.barometr.identity.internal.twofactor

import java.time.Instant
import java.util.UUID

/** Where the devices allowed to skip the second factor are kept. */
interface TrustedDevices {

    fun remember(device: RememberedDevice): RememberedDevice

    /** The live, unexpired trust this token names, or null when it names none. */
    fun byTokenHash(hash: String, now: Instant): RememberedDevice?

    fun markUsed(id: UUID, at: Instant)

    fun liveFor(userId: UUID, now: Instant): List<RememberedDevice>

    /** Ends one, and says whether there was anything live to end. */
    fun revoke(userId: UUID, id: UUID, at: Instant): Boolean

    /** Ends every one of this account's, and says how many. */
    fun revokeAll(userId: UUID, at: Instant): Int
}
