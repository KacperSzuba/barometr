package pl.barometr.identity.internal.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * How long a signed-in device may go quiet before it has to sign in again, bound from
 * the `app.identity.session` block.
 *
 * The specification asks for this to be configurable per workspace. There are no
 * workspaces yet, so it is one setting for the deployment — and it is a property rather
 * than a constant precisely so that the day workspaces arrive, this becomes their
 * default rather than a number compiled into the rotation.
 */
@ConfigurationProperties("app.identity.session")
data class SessionProperties(
    /**
     * Fourteen days: comfortably longer than a holiday, comfortably shorter than the
     * thirty-day refresh token, so an unused session ends by going quiet rather than by
     * running out.
     */
    val idleTimeout: Duration = Duration.ofDays(14),
) {
    init {
        require(!idleTimeout.isNegative && !idleTimeout.isZero) { "An idle timeout is a positive duration" }
    }
}
