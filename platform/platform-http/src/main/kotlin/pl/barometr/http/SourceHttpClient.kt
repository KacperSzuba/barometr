package pl.barometr.http

import java.net.URI
import java.time.Duration

data class HttpFetch(
    val url: URI,
    /** Previous ETag, turning this into a conditional request. */
    val etag: String? = null,
    val lastModified: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

enum class RefusalReason {
    /** robots.txt disallows this path for our agent. */
    ROBOTS_DISALLOWED,

    /**
     * The publisher reserved rights against text and data mining. Detectable only
     * from the response, so the content is fetched and then discarded unread.
     */
    TDM_RESERVED,
}

sealed interface HttpOutcome {

    /** Plain class: equality over a [ByteArray] body would compare references. */
    class Fetched(
        val body: ByteArray,
        val contentType: String?,
        val etag: String?,
        val lastModified: String?,
    ) : HttpOutcome

    /** 304. The cheapest possible answer, and the reason ETags are tracked at all. */
    data object NotModified : HttpOutcome

    data class Refused(val reason: RefusalReason, val detail: String) : HttpOutcome

    data class Failed(val statusCode: Int?, val detail: String) : HttpOutcome
}

data class HttpPolicy(
    /** Enforced per host by a token bucket, not left to each connector. */
    val requestsPerSecond: Double,
    val userAgent: String = DEFAULT_USER_AGENT,
    val maxAttempts: Int = 4,
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val requestTimeout: Duration = Duration.ofSeconds(60),
    val robots: RobotsPolicy = RobotsPolicy.Respect,
) {
    companion object {
        const val DEFAULT_USER_AGENT = "BarometrBot/1.0 (+https://barometr.pl/bot)"
    }
}

/**
 * Whether this source's robots.txt is honoured.
 *
 * A sealed type rather than a boolean, because the two states are not symmetric.
 * Respecting robots.txt needs no justification; overriding it does — so the
 * override cannot be expressed without writing one down, and the written reason
 * travels with the configuration into logs and into the source registry.
 *
 * Deliberately impossible to set quietly. An exemption that nobody can see is the
 * shape this takes when someone is working around an access restriction rather
 * than standing on a right to the data; an exemption that announces itself on
 * every run is the shape it takes when the right is real.
 */
sealed interface RobotsPolicy {

    data object Respect : RobotsPolicy

    /**
     * Reads the source despite its robots.txt, on a stated basis.
     *
     * [legalBasis] is what makes this defensible: the ground the operator of this
     * system stands on — a statutory right of access, or permission granted by the
     * source. It is recorded, logged and answerable, not a flag.
     */
    data class Exempt(val legalBasis: String) : RobotsPolicy {
        init {
            require(legalBasis.isNotBlank()) {
                "A robots.txt exemption requires a written legal basis"
            }
            require(legalBasis.length >= MINIMUM_BASIS_LENGTH) {
                "State the actual basis, not a placeholder: '$legalBasis'"
            }
        }

        private companion object {
            const val MINIMUM_BASIS_LENGTH = 20
        }
    }
}

/**
 * The single door every connector goes through to reach the outside world.
 *
 * Rate limiting, retries with backoff, conditional requests and the robots/TDM
 * gate live here rather than in each connector — twenty connectors would
 * otherwise mean twenty subtly different retry loops, and the one that forgets
 * to honour `Retry-After` is the one that gets the whole system blocked.
 */
interface SourceHttpClient {
    fun fetch(request: HttpFetch): HttpOutcome
}
