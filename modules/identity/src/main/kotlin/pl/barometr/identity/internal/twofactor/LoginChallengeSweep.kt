package pl.barometr.identity.internal.twofactor

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Clears out the challenges nobody finished.
 *
 * They are already worthless — expiry is checked when one is answered, not when one is
 * deleted — so this is housekeeping rather than a guard: an abandoned sign-in leaves a
 * row, most sign-ins that ask for a second factor are abandoned at some point, and
 * without this the table only grows.
 *
 * No lock across instances, deliberately. Two of these running at once delete the same
 * rows and the second finds none, which costs one statement and needs no coordination —
 * unlike the sweeps that queue work, where a second run would queue it twice.
 */
@Component
class LoginChallengeSweep(
    private val challenges: LoginChallenges,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.identity.two-factor.sweep-interval:PT1H}", initialDelay = 300_000)
    fun deleteFinishedChallenges() {
        val deleted = challenges.deleteFinishedBefore(clock.instant())

        if (deleted > 0) log.debug("Cleared {} finished sign-in challenges", deleted)
    }
}
