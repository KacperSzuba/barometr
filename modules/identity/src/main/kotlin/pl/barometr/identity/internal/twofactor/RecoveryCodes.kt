package pl.barometr.identity.internal.twofactor

import java.time.Instant
import java.util.UUID

/** Where the one-time codes live. Only their hashes are ever stored. */
interface RecoveryCodes {

    /** Replaces every code this account has: minting a new set retires the old one. */
    fun replaceAll(userId: UUID, hashes: List<String>, at: Instant)

    /**
     * Spends one code, if it is this account's and has not been spent.
     *
     * @return false when the code is unknown or already used — which the caller must not
     *   tell apart out loud either.
     */
    fun consume(userId: UUID, hash: String, at: Instant): Boolean

    fun unusedCount(userId: UUID): Int

    fun deleteAll(userId: UUID)
}
