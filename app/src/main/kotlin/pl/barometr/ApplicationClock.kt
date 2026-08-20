package pl.barometr

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The one clock the application reads.
 *
 * Injected rather than called statically, so that anything time-dependent can be
 * tested by handing it `Clock.fixed(...)` instead of by sleeping or by rewriting
 * rows. The queue's backoff test used to move `run_after` backwards with an `UPDATE`
 * for want of this, and the refresh-token grace window — a security behaviour — was
 * not testable at all.
 *
 * UTC, because every timestamp in this system is stored as `timestamptz` and read
 * back in UTC; a clock in the host's zone would put the difference somewhere nobody
 * is looking.
 */
@Configuration
class ApplicationClock {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
