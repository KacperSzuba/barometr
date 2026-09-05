package pl.barometr.identity.internal.twofactor

import java.time.Instant
import java.util.UUID

/** Where the gap between the two factors is remembered. */
interface LoginChallenges {

    fun open(challenge: LoginChallenge): LoginChallenge

    /** Holds the row for the rest of the transaction, so two answers cannot both count. */
    fun byIdForUpdate(id: UUID): LoginChallenge?

    fun recordAttempt(id: UUID): Int

    fun consume(id: UUID, at: Instant): Boolean

    /** Spent and expired challenges, cleared out. Returns how many rows went. */
    fun deleteFinishedBefore(cutoff: Instant): Int
}
