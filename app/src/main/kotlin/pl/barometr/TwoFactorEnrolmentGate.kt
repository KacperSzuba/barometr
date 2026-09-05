package pl.barometr

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import pl.barometr.identity.api.JwtClaims

/**
 * Lets somebody whose workspace insists on a second factor in far enough to set one up,
 * and no further.
 *
 * This is what the specification means by "enforcement at workspace level blocks access
 * until it is configured". Refusing the sign-in outright would leave such a person with
 * no way to comply — including the administrator who has just turned the policy on — so
 * identity signs them in with a token that says `enrol`, and this is where that claim
 * stops being advice.
 *
 * **In the application rather than in identity**, for the reason the security chain is:
 * only the application knows every context's routes, and the list below is a statement
 * about all of them. Identity decides *whether* somebody must enrol; this decides what
 * that costs them.
 *
 * It runs innermost, after authentication and inside [AuditFilter], so a refusal is
 * recorded like every other.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class TwoFactorEnrolmentGate : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        if (mustEnrol() && !isAllowedWhileEnrolling(request)) {
            response.status = HttpServletResponse.SC_FORBIDDEN
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = Charsets.UTF_8.name()
            response.writer.write("""{"error":"two_factor_setup_required"}""")
            return
        }

        chain.doFilter(request, response)
    }

    private fun mustEnrol(): Boolean {
        val token = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken ?: return false

        return (token.token as Jwt).getClaimAsBoolean(JwtClaims.ENROLMENT_REQUIRED) == true
    }

    /**
     * What such a caller may still reach: everything they need to comply, and the two
     * things it would be cruel to take away while they do — knowing who they are, and
     * signing out.
     */
    private fun isAllowedWhileEnrolling(request: HttpServletRequest): Boolean =
        ALLOWED.any { request.requestURI.startsWith(it) }

    private companion object {
        val ALLOWED = listOf(
            "/api/v1/auth/2fa",
            "/api/v1/auth/logout",
            "/api/v1/auth/refresh",
            "/api/v1/me",
        )
    }
}
