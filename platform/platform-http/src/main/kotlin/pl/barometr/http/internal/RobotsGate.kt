package pl.barometr.http.internal

import crawlercommons.robots.BaseRobotRules
import crawlercommons.robots.SimpleRobotRulesParser
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * robots.txt compliance, delegated to crawler-commons.
 *
 * Hand-writing this parser was a mistake worth naming: a prefix matcher looks right
 * on the common cases and silently ignores wildcard rules such as a `*.pdf$`
 * disallow, so the crawler fetches exactly what it was told not to. crawler-commons
 * is the parser Nutch and StormCrawler use — wildcards, `$` anchors, agent-token
 * matching, malformed files and the HTTP-status semantics all come with it.
 *
 * One gate for the whole application, not one per client: rules belong to a host,
 * so a gate per connector meant every connector re-fetching robots.txt for a host
 * another had already asked — the rate limiters had been shared for the same reason
 * from the start. The cache is keyed by agent as well as origin, because a
 * robots.txt answers differently depending on who is asking.
 */
class RobotsGate(
    private val restClient: RestClient,
    private val clock: Clock,
    private val cacheTtl: Duration = Duration.ofHours(12),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val parser = SimpleRobotRulesParser()
    private val cache = ConcurrentHashMap<CacheKey, CachedRules>()

    fun allows(url: URI, userAgent: String): Boolean =
        rulesFor(url, userAgent).isAllowed(url.toString())

    /** How long the source asked us to wait between requests, if it said so. */
    fun crawlDelay(url: URI, userAgent: String): Duration? =
        rulesFor(url, userAgent).crawlDelay
            .takeIf { it != BaseRobotRules.UNSET_CRAWL_DELAY && it > 0 }
            ?.let(Duration::ofMillis)

    private fun rulesFor(url: URI, userAgent: String): BaseRobotRules {
        val key = CacheKey("${url.scheme}://${url.authority}", userAgent)
        cache[key]?.takeIf { it.fetchedAt.plus(cacheTtl) > clock.instant() }?.let { return it.rules }

        val rules = fetchRules(key)
        cache[key] = CachedRules(rules, clock.instant())
        return rules
    }

    private fun fetchRules(key: CacheKey): BaseRobotRules =
        try {
            restClient.get()
                .uri("${key.origin}/robots.txt")
                .header("user-agent", key.userAgent)
                .exchange { _, response ->
                    val status = response.statusCode
                    if (status.is2xxSuccessful) {
                        parser.parseContent(
                            "${key.origin}/robots.txt",
                            response.bodyTo(ByteArray::class.java) ?: ByteArray(0),
                            "text/plain",
                            key.userAgent,
                        )
                    } else {
                        // Encodes the whole convention: 4xx means no restrictions,
                        // 5xx means treat the site as closed, redirects and the rest
                        // handled too. This branch used to be four hand-written
                        // cases, three of which were guesses.
                        parser.failedFetch(status.value())
                    }
                }
        } catch (failure: Exception) {
            // Unreachable robots.txt is not a licence to crawl hard, but it is also
            // not a refusal — the source may simply be having a bad minute. Logged
            // because the alternative is a host we quietly stop reading, or quietly
            // start reading, for a reason nobody can see.
            log.warn("Could not read {}/robots.txt; treating the site as closed", key.origin, failure)
            parser.failedFetch(HTTP_UNAVAILABLE)
        }

    private data class CacheKey(val origin: String, val userAgent: String)

    private data class CachedRules(val rules: BaseRobotRules, val fetchedAt: java.time.Instant)

    private companion object {
        const val HTTP_UNAVAILABLE = 503
    }
}
