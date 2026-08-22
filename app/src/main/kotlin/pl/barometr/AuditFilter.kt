package pl.barometr

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import pl.barometr.audit.api.AuditOutcome
import pl.barometr.audit.api.AuditTrail

/**
 * Records what was attempted, from outside every context.
 *
 * Here rather than in the contexts because only the application sees every route, and
 * because a trail assembled from calls each context remembered to make is one that goes
 * quiet the first time somebody forgets. A filter cannot forget.
 *
 * **This one only ever sees requests that got through.** It sits inside the security
 * chain, so a request refused for lack of authority never reaches it — and those are
 * the entries this feature exists for. They are recorded where they happen instead, by
 * the two handlers in [ApplicationSecurityConfig] that produce them, which is also the
 * only place the caller is still known.
 *
 * What is recorded here: everything that changes something, and everything that failed.
 * A `GET` that succeeded is a page view, and a log holding all of them buries the entry
 * somebody is looking for under a hundred thousand that answer nothing.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class AuditFilter(private val trail: AuditTrail) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        chain.doFilter(request, response)

        val outcome = outcomeOf(response.status)
        if (outcome != AuditOutcome.SUCCEEDED || request.method !in READS) {
            trail.record(AuditedRequest.of(request, outcome, response.status))
        }
    }

    private fun outcomeOf(status: Int): AuditOutcome = when {
        // Denials do reach here when a controller decides one for itself — reading
        // somebody else's profile answers "not found" rather than "forbidden", but an
        // endpoint that answers 403 directly is still a refusal and reads as one.
        status in DENIALS -> AuditOutcome.DENIED
        status >= SERVER_ERROR -> AuditOutcome.FAILED
        status >= CLIENT_ERROR -> AuditOutcome.REJECTED
        else -> AuditOutcome.SUCCEEDED
    }

    private companion object {
        /** Methods that read. Everything else changes something. */
        val READS = setOf("GET", "HEAD", "OPTIONS")

        val DENIALS = setOf(401, 403)

        const val CLIENT_ERROR = 400
        const val SERVER_ERROR = 500
    }
}
