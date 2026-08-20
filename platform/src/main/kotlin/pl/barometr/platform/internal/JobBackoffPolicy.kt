package pl.barometr.platform.internal

import org.springframework.stereotype.Component
import java.time.Duration
import kotlin.math.min
import kotlin.random.Random

/**
 * How long a failed job waits before its next attempt.
 *
 * Extracted from the repository because it is a decision, not persistence: it has
 * its own reasoning, its own tuning and its own reason to be tested, none of which
 * belong beside an `UPDATE` statement.
 */
@Component
class JobBackoffPolicy(
    private val base: Duration = Duration.ofSeconds(5),
    private val ceiling: Duration = Duration.ofHours(1),
) {

    /**
     * Exponential, capped, with jitter.
     *
     * The jitter matters more than the curve. Without it, a hundred jobs that failed
     * against one source all retry in the same second and reproduce the outage they
     * were backing off from — so each wait is spread across the upper half of its
     * exponential window rather than landing exactly on it.
     */
    fun delayAfter(attempts: Int): Duration {
        val window = min(base.seconds shl min(attempts, MAX_SHIFT), ceiling.seconds)
        val half = window / 2
        return Duration.ofSeconds(half + Random.nextLong(half + 1))
    }

    private companion object {
        const val MAX_SHIFT = 10
    }
}
