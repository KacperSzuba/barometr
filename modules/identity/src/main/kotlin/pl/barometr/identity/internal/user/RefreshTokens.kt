package pl.barometr.identity.internal.user

import java.time.Instant
import java.util.UUID

/**
 * What refresh-token rotation needs from storage.
 *
 * Every method here exists because rotation is a concurrency problem: the row is
 * read under a lock, marked used, and the family is revoked as a unit. Spelling
 * those out as operations — rather than leaning on an ORM's dirty checking — keeps
 * the sequence explicit at the call site and survives a change of persistence.
 */
interface RefreshTokens {

    /**
     * Reads a token and holds its row for the rest of the transaction.
     *
     * The lock is the whole mechanism: two refreshes presenting the same token are
     * serialised here, so the second sees what the first decided rather than racing
     * it. Without it both mint a successor and one of them looks like theft.
     */
    fun byTokenHashForUpdate(hash: String): RefreshToken?

    fun add(token: RefreshToken): RefreshToken

    /** Records first use. A second use is either a request race or theft. */
    fun markUsed(id: UUID, at: Instant)

    /** Revokes every token descending from one login. Returns how many were live. */
    fun revokeFamily(familyId: UUID, at: Instant): Int
}
