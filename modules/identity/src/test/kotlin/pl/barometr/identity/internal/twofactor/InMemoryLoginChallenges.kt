package pl.barometr.identity.internal.twofactor

import java.time.Instant
import java.util.UUID

/**
 * [LoginChallenges] in a map.
 *
 * The row lock the real one takes has no equivalent here and needs none: these tests
 * drive the calls in the order the lock would have produced, and what they are about is
 * the policy — expired, spent, out of attempts — rather than the database's willingness
 * to serialise two callers.
 */
class InMemoryLoginChallenges : LoginChallenges {
    private val stored = mutableMapOf<UUID, LoginChallenge>()

    override fun open(challenge: LoginChallenge): LoginChallenge {
        stored[challenge.id] = challenge
        return challenge
    }

    override fun byIdForUpdate(id: UUID): LoginChallenge? = stored[id]

    override fun recordAttempt(id: UUID): Int {
        val challenge = stored[id] ?: return 0
        val attempted = challenge.copy(attempts = challenge.attempts + 1)
        stored[id] = attempted
        return attempted.attempts
    }

    override fun consume(id: UUID, at: Instant): Boolean {
        val open = stored[id]?.takeIf { it.consumedAt == null } ?: return false
        stored[id] = open.copy(consumedAt = at)
        return true
    }

    override fun deleteFinishedBefore(cutoff: Instant): Int {
        val finished = stored.values.filter { it.expiresAt.isBefore(cutoff) }.map { it.id }
        finished.forEach(stored::remove)
        return finished.size
    }
}
