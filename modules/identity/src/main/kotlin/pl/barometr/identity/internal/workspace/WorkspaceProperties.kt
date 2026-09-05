package pl.barometr.identity.internal.workspace

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * What a workspace starts with and how long an invitation lives, bound from the
 * `app.identity.workspace` block.
 *
 * Both are defaults a deployment may move rather than facts about the domain, which is
 * why neither is a constant in the service.
 */
@ConfigurationProperties("app.identity.workspace")
data class WorkspaceProperties(
    /**
     * Seats a new workspace has before anybody buys more. Five: enough for the team that
     * signs up on a Friday afternoon to invite their colleagues without waiting for an
     * invoice, and small enough that buying more is a conversation somebody has.
     */
    val defaultSeats: Int = 5,
    /**
     * How long an invitation link works. Fourteen days: long enough to survive a
     * holiday, short enough that a link in an old mailbox is not a seat.
     */
    val invitationTtl: Duration = Duration.ofDays(14),
    /** Where an invitation link points, without a trailing slash. */
    val invitationBaseUrl: String = "",
) {
    init {
        require(defaultSeats in 1..10_000) { "A workspace starts with between 1 and 10000 seats" }
        require(!invitationTtl.isNegative && !invitationTtl.isZero) { "An invitation lives a positive duration" }
    }
}
