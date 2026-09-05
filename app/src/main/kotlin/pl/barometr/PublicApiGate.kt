package pl.barometr

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import pl.barometr.identity.api.ApiKeyGrant
import pl.barometr.identity.api.ApiKeys
import pl.barometr.identity.api.ApiScope
import pl.barometr.identity.api.ApiTier
import pl.barometr.platform.RateLimit
import pl.barometr.platform.RateLimiter
import java.time.Duration

/**
 * What the public API costs: a key or an address, a rate, and a line saying who to credit.
 *
 * **Anonymous callers are let in.** A public API that requires registration before the
 * first `curl` is one nobody evaluates — so no key means sixty requests an hour by
 * address, which is enough to try it and not enough to build on. A key raises the rate
 * and nothing else: every tier sees the same data, because that is what makes it public.
 *
 * **In the application, like the security chain and for the same reason.** Only the
 * application knows which routes are the public ones; identity knows what a key is worth
 * and platform knows how to count. This is where those three meet.
 *
 * The `X-RateLimit-*` headers go out on every answer, refused or not: a client that has
 * been turned away needs to know when to come back more than one that got through.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class PublicApiGate(
    private val keys: ApiKeys,
    private val limiter: RateLimiter,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith(PUBLIC_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val presented = request.getHeader(KEY_HEADER)?.trim()?.takeIf { it.isNotEmpty() }
        val grant = presented?.let(keys::grantFor)

        if (presented != null && grant == null) {
            // Unknown, revoked or expired — one answer for all three, because telling a
            // caller which would be telling them something about keys that exist.
            return refuse(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_api_key")
        }

        val scope = scopeFor(request)
        if (grant != null && !grant.permits(scope)) {
            return refuse(response, HttpServletResponse.SC_FORBIDDEN, "api_scope_required")
        }
        if (grant == null && scope != ApiScope.READ) {
            // The bulk downloads are where a public API stops being cheap to serve, so
            // they are the one thing a key is actually required for.
            return refuse(response, HttpServletResponse.SC_UNAUTHORIZED, "api_key_required")
        }

        val tier = grant?.tier ?: ApiTier.ANONYMOUS
        val limit = limiter.consume(bucketFor(grant, request), tier.requestsPerHour, WINDOW)

        response.setHeader(ATTRIBUTION_HEADER, ATTRIBUTION)
        rateLimitHeaders(response, limit)

        if (!limit.allowed) {
            response.setHeader(RETRY_AFTER, WINDOW.seconds.toString())
            log.debug("Public API refused {} on {}", tier.wireName, request.requestURI)
            return refuse(response, TOO_MANY_REQUESTS, "rate_limited")
        }

        chain.doFilter(request, response)
    }

    /**
     * Which scope this route needs.
     *
     * By shape rather than by a list of paths: a whole-dataset download is the expensive
     * kind, and it is the one that ends in `/csv`. A list that had to be edited for every
     * new route would be a list somebody forgets.
     */
    private fun scopeFor(request: HttpServletRequest): ApiScope =
        if (request.requestURI.endsWith(BULK_SUFFIX)) ApiScope.BULK else ApiScope.READ

    /**
     * Who is being limited: the key when there is one, the address when there is not.
     *
     * An address is a coarse bucket — a university behind one gateway shares it — which
     * is the honest cost of letting anybody in without registering, and the reason a key
     * raises the rate tenfold.
     */
    private fun bucketFor(grant: ApiKeyGrant?, request: HttpServletRequest): String =
        grant?.let { "key:${it.keyId}" } ?: "ip:${request.remoteAddr}"

    private fun rateLimitHeaders(response: HttpServletResponse, limit: RateLimit) {
        response.setHeader(LIMIT, limit.limit.toString())
        response.setHeader(REMAINING, limit.remaining.toString())
        response.setHeader(RESET, limit.resetAt.epochSecond.toString())
    }

    private fun refuse(response: HttpServletResponse, status: Int, error: String) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write("""{"error":"$error"}""")
    }

    private companion object {
        const val PUBLIC_PREFIX = "/api/v1/public/"
        const val BULK_SUFFIX = "/csv"

        const val KEY_HEADER = "X-Api-Key"
        const val LIMIT = "X-RateLimit-Limit"
        const val REMAINING = "X-RateLimit-Remaining"
        const val RESET = "X-RateLimit-Reset"
        const val RETRY_AFTER = "Retry-After"

        /**
         * The condition of use, on every response.
         *
         * In a header rather than only in a document, because the terms nobody reads are
         * the terms in a document: an integrator sees this the first time they look at a
         * response, which is when crediting the source is still easy to build in.
         */
        const val ATTRIBUTION_HEADER = "X-Attribution"
        const val ATTRIBUTION = "Źródło: Barometr (barometr.example) — proszę podać przy publikacji"

        const val TOO_MANY_REQUESTS = 429

        /** An hour, which is the unit every rate in [ApiTier] is stated in. */
        val WINDOW: Duration = Duration.ofHours(1)
    }
}
