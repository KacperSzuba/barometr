package pl.barometr.identity.internal.auth

import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.barometr.identity.api.JwtClaims
import pl.barometr.identity.api.callerOf
import pl.barometr.identity.internal.user.SignedInSession
import java.security.Principal
import java.util.UUID

/**
 * Where this account is signed in, and ending any of it from here.
 *
 * The list marks the session the request arrived on, which is what makes "sign out
 * everywhere else" a safe button to press: the reader keeps the tab they are pressing it
 * in. That mark comes from the `sid` claim in the caller's own access token, so it
 * cannot be spoofed by asking about somebody else's session.
 */
@RestController
@RequestMapping("/api/v1/sessions")
class SessionController(private val sessions: SignedInSessions) {

    @GetMapping
    fun list(caller: Principal, @AuthenticationPrincipal jwt: Jwt?): List<SessionResponse> {
        val current = sessionOf(jwt)

        return sessions.sessionsOf(callerOf(caller)).map { describe(it, current) }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun endSession(caller: Principal, @PathVariable id: UUID) {
        sessions.endSession(callerOf(caller), id)
    }

    /**
     * Everything except the one this request came on.
     *
     * A caller whose token carries no session — one minted before sessions existed —
     * would end every session including their own, which is a worse surprise than being
     * told to sign in again. So the route refuses rather than guessing.
     */
    @DeleteMapping
    fun endOthers(caller: Principal, @AuthenticationPrincipal jwt: Jwt?): EndedResponse {
        val current = sessionOf(jwt) ?: throw UnknownSessionException("the caller's own")

        return EndedResponse(sessions.endEverySessionExcept(callerOf(caller), current))
    }

    private fun sessionOf(jwt: Jwt?): UUID? =
        jwt?.getClaimAsString(JwtClaims.SESSION)?.let { claim -> runCatching { UUID.fromString(claim) }.getOrNull() }

    private fun describe(session: SignedInSession, current: UUID?) = SessionResponse(
        id = session.familyId,
        current = session.familyId == current,
        userAgent = session.userAgent,
        clientIp = session.clientIp,
        approximateLocation = sessions.locationOf(session),
        createdAt = session.createdAt.toString(),
        lastSeenAt = session.lastSeenAt.toString(),
    )

    data class SessionResponse(
        val id: UUID,
        /** True for the session this request was made on. */
        val current: Boolean,
        /** As the client sent it, unparsed: a user agent is not a device name. */
        val userAgent: String?,
        val clientIp: String?,
        /**
         * Roughly where that address is, or null. A guess shown beside the address rather
         * than instead of it — see [pl.barometr.identity.internal.user.ApproximateLocations].
         */
        val approximateLocation: String?,
        val createdAt: String,
        val lastSeenAt: String,
    )

    data class EndedResponse(val ended: Int)
}
