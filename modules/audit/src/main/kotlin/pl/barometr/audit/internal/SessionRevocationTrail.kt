package pl.barometr.audit.internal

import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import pl.barometr.audit.api.AuditOutcome
import pl.barometr.audit.api.AuditTrail
import pl.barometr.audit.api.AuditableAttempt
import pl.barometr.identity.api.UserSessionsRevoked
import java.util.Locale

/**
 * Records the sessions this system ended without being asked.
 *
 * Everything else in the trail arrives through the filter that records requests, and
 * that is the right shape for a log of what people did. It leaves out what the system
 * decided: a refresh token replayed is a theft this application acts on by ending every
 * session the account has, and the only thing a person could see afterwards was one
 * refused refresh — which is what an expired token looks like as well.
 *
 * **A logout is recorded twice, deliberately.** The request is already in the trail as a
 * `POST` that succeeded; this is what it *did*, which is a different fact and the one
 * that answers "why am I signed out on my other laptop". The reasons that no request
 * explains at all — a replay, an idle device — have nowhere else to be recorded.
 *
 * Written under the account it happened to, because the trail is read per actor and
 * exported to the person who owns it: this is their answer, not an operator's.
 */
@Component
class SessionRevocationTrail(private val trail: AuditTrail) {

    @ApplicationModuleListener
    fun recordRevokedSessions(revoked: UserSessionsRevoked) {
        trail.record(
            AuditableAttempt(
                actor = revoked.userId,
                action = ACTION,
                // The collection the person can see, and where the effect is visible.
                resource = SESSIONS,
                outcome = AuditOutcome.SUCCEEDED,
                detail = revoked.reason.name.lowercase(Locale.ROOT),
            ),
        )
    }

    private companion object {
        const val ACTION = "REVOKE"
        const val SESSIONS = "/api/v1/sessions"
    }
}
