package pl.barometr.identity.internal.workspace

import java.time.Duration
import java.time.Instant

/**
 * One organisation's account.
 *
 * [requireTwoFactor] and [sessionIdleTimeout] are the two things an institutional
 * customer asks about before signing anything, and both are decisions the customer makes
 * rather than the product: "we insist on a second factor" and "our sessions end sooner
 * than your default".
 *
 * A null timeout means "whatever the deployment says", which is deliberately different
 * from a workspace that has chosen the same number — the first follows the default when
 * it moves and the second does not.
 */
data class Workspace(
    val id: WorkspaceId,
    val name: String,
    val seats: Int,
    val requireTwoFactor: Boolean,
    val sessionIdleTimeout: Duration?,
    val createdAt: Instant,
)
