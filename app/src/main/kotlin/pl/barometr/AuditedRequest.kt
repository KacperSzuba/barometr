package pl.barometr

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import pl.barometr.audit.api.AuditOutcome
import pl.barometr.audit.api.AuditableAttempt
import pl.barometr.identity.api.UserId
import java.util.UUID

/**
 * One request, described the way the audit trail wants it.
 *
 * In one place because three callers build it — the filter and the two denial handlers
 * — and an entry recorded from a refusal that described its caller differently from one
 * recorded on the way out would make the two impossible to read side by side.
 */
object AuditedRequest {

    fun of(request: HttpServletRequest, outcome: AuditOutcome, status: Int): AuditableAttempt {
        val name = SecurityContextHolder.getContext().authentication
            ?.takeIf { it.isAuthenticated }
            ?.name

        return AuditableAttempt(
            actor = name?.let(::asUserId),
            // What they presented as when it was not an identifier: the anonymous
            // principal, or a name from a token this system did not mint. A run of
            // failed sign-ins is only recognisable as one if something separates them.
            actorLabel = name?.takeIf { asUserId(it) == null },
            action = request.method,
            resource = request.requestURI,
            outcome = outcome,
            status = status,
            // The peer, not a forwarded header: without a list of trusted proxies that
            // header is one anybody can set, and a claimed address recorded as a fact
            // is worse than no address at all.
            peer = request.remoteAddr,
        )
    }

    private fun asUserId(name: String): UserId? =
        runCatching { UserId(UUID.fromString(name)) }.getOrNull()
}
