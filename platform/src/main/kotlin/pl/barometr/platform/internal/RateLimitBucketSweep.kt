package pl.barometr.platform.internal

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.barometr.platform.RateLimiter
import java.time.Duration

/**
 * Forgets the buckets nobody is using.
 *
 * A bucket at full is indistinguishable from one that never existed, so keeping it buys
 * nothing — and an anonymous caller's bucket is keyed by their address, which is personal
 * data this system has no reason to hold for a month because somebody once tried the API
 * from a terminal.
 *
 * No lock across instances: two sweeps delete the same rows and the second finds none,
 * which costs one statement and needs no coordination.
 */
@Component
class RateLimitBucketSweep(private val limiter: RateLimiter) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.platform.rate-limit.sweep-interval:PT12H}", initialDelay = 1_200_000)
    fun forgetIdleBuckets() {
        val forgotten = limiter.forgetIdleBuckets(IDLE_FOR)

        if (forgotten > 0) log.debug("Forgot {} idle rate-limit bucket(s)", forgotten)
    }

    private companion object {
        /**
         * A day: several times the longest window any tier uses, so a bucket is only ever
         * forgotten when it is certainly back at full.
         */
        val IDLE_FOR: Duration = Duration.ofDays(1)
    }
}
