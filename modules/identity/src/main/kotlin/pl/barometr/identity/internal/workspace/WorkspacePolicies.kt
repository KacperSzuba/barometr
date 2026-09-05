package pl.barometr.identity.internal.workspace

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import java.time.Duration

/**
 * The two questions a workspace's policy is asked, on paths where the answer has to be
 * cheap.
 *
 * Both run on every sign-in and every refresh, which is why they are single indexed
 * queries rather than a walk over somebody's memberships — and why they live in a service
 * of their own rather than on [TeamWorkspaces], whose methods are all about somebody
 * administering something.
 *
 * **Somebody in two workspaces is subject to both.** The stricter answer wins in each
 * case: a second factor is required if any workspace requires it, and the idle timeout is
 * the shortest anybody has chosen. The alternative — the newest, or the first — would
 * mean joining a second workspace could quietly relax the first one's policy.
 */
@Service
@Transactional(readOnly = true)
class WorkspacePolicies(private val workspaces: Workspaces) {

    fun requiresTwoFactor(user: UserId): Boolean = workspaces.anyRequiresTwoFactor(user)

    /**
     * The shortest idle timeout this account's workspaces insist on, or [orDefault] when
     * none of them has an opinion.
     */
    fun idleTimeoutFor(user: UserId, orDefault: Duration): Duration =
        workspaces.strictestIdleTimeout(user)?.let { minOf(it, orDefault) } ?: orDefault
}
