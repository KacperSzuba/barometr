package pl.barometr.http.internal

import org.springframework.core.retry.RetryException
import org.springframework.core.retry.RetryPolicy
import org.springframework.core.retry.RetryTemplate
import org.springframework.http.HttpHeaders
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import pl.barometr.http.HttpFetch
import pl.barometr.http.HttpOutcome
import pl.barometr.http.HttpPolicy
import pl.barometr.http.RefusalReason
import pl.barometr.http.RobotsPolicy
import pl.barometr.http.SourceHttpClient
import java.time.Duration

/**
 * The single door connectors go through to reach the outside world.
 *
 * Built on `RestClient`, so every request is covered by Boot's Micrometer
 * instrumentation — an `http.client.requests` timer and a trace span per fetch.
 * That is what lets a document be followed from fetch to alert by trace id, and
 * it is the reason a hand-built client on `java.net.http` was the wrong choice:
 * it emitted nothing.
 *
 * Retries come from `RetryTemplate` in Spring Framework core, rate limiting from
 * Resilience4j, robots.txt from crawler-commons. What is left here is the part
 * that is genuinely ours: turning HTTP into the small vocabulary connectors need
 * — fetched, unchanged, refused, failed.
 */
class RestClientSourceHttpClient(
    private val restClient: RestClient,
    private val policy: HttpPolicy,
    private val rateLimiters: HostRateLimiters,
    private val robots: RobotsGate,
) : SourceHttpClient {

    private val retryTemplate = RetryTemplate(
        RetryPolicy.builder()
            .maxRetries(policy.maxAttempts.toLong() - 1)
            .delay(INITIAL_DELAY)
            .multiplier(2.0)
            .maxDelay(MAX_DELAY)
            // Jitter is in the framework's policy, so the "every connector retries
            // in the same second" problem is handled without writing it out.
            .jitter(JITTER)
            .includes(TransientHttpFailure::class.java, ResourceAccessException::class.java)
            .build(),
    )

    override fun fetch(request: HttpFetch): HttpOutcome {
        if (policy.robots is RobotsPolicy.Respect && !robots.allows(request.url, policy.userAgent)) {
            return HttpOutcome.Refused(
                RefusalReason.ROBOTS_DISALLOWED,
                "robots.txt disallows ${request.url.rawPath} for ${policy.userAgent}",
            )
        }

        return try {
            retryTemplate.execute { attempt(request) }
        } catch (exhausted: RetryException) {
            HttpOutcome.Failed(
                statusCode = (exhausted.lastException as? TransientHttpFailure)?.statusCode,
                detail = "${exhausted.lastException?.message} after ${exhausted.retryCount + 1} attempts",
            )
        }
    }

    private fun attempt(request: HttpFetch): HttpOutcome {
        // A source's own Crawl-delay outranks our configured pace.
        val declared = robots.crawlDelay(request.url, policy.userAgent)?.let { 1_000.0 / it.toMillis() }
        val rate = minOf(declared ?: policy.requestsPerSecond, policy.requestsPerSecond)
        rateLimiters.acquire(request.url.authority, rate)

        return restClient.get()
            .uri(request.url)
            .headers { headers ->
                headers.set(HttpHeaders.USER_AGENT, policy.userAgent)
                request.headers.forEach { (name, value) -> headers.set(name, value) }
                // Conditional request: an unchanged resource costs one 304 instead
                // of a download, which is what makes a 15-minute poll affordable.
                request.etag?.let { headers.setIfNoneMatch(it) }
                request.lastModified?.let { headers.set(HttpHeaders.IF_MODIFIED_SINCE, it) }
            }
            .exchange { _, response ->
                val status = response.statusCode
                when {
                    status.value() == NOT_MODIFIED -> HttpOutcome.NotModified
                    status.is2xxSuccessful -> interpretSuccess(response.headers, response.bodyTo(ByteArray::class.java))
                    status.value() == TOO_MANY_REQUESTS || status.is5xxServerError -> {
                        // `Retry-After` is honoured here rather than in the retry
                        // policy, which has no hook for a server-supplied delay.
                        // Ignoring it is the fastest way to get blocked outright.
                        retryAfter(response.headers)?.let { Thread.sleep(it.toMillis()) }
                        throw TransientHttpFailure(status.value())
                    }
                    else -> HttpOutcome.Failed(status.value(), "status ${status.value()}")
                }
            }
    }

    private fun interpretSuccess(headers: HttpHeaders, body: ByteArray?): HttpOutcome {
        // Machine-readable rights reservation under the DSM text-and-data-mining
        // exception. Only readable from the response, so the bytes arrive and are
        // then dropped unread — the legal boundary enforced in code, not in a note.
        val reservation = headers.getFirst("tdm-reservation")
        val robotsTag = headers.getFirst("x-robots-tag")?.lowercase()
        if (reservation == "1" || robotsTag?.contains("noai") == true) {
            return HttpOutcome.Refused(
                RefusalReason.TDM_RESERVED,
                "publisher reserved TDM rights (tdm-reservation=$reservation, x-robots-tag=$robotsTag)",
            )
        }

        return HttpOutcome.Fetched(
            body = body ?: ByteArray(0),
            contentType = headers.contentType?.toString(),
            etag = headers.eTag,
            lastModified = headers.getFirst(HttpHeaders.LAST_MODIFIED),
        )
    }

    private fun retryAfter(headers: HttpHeaders): Duration? =
        headers.getFirst(HttpHeaders.RETRY_AFTER)
            ?.toLongOrNull()
            ?.let(Duration::ofSeconds)
            ?.takeIf { it <= MAX_RETRY_AFTER }

    /** Signals the retry template that the source may recover. */
    private class TransientHttpFailure(val statusCode: Int) :
        RuntimeException("transient status $statusCode")

    private companion object {
        const val NOT_MODIFIED = 304
        const val TOO_MANY_REQUESTS = 429
        val INITIAL_DELAY: Duration = Duration.ofMillis(500)
        val MAX_DELAY: Duration = Duration.ofSeconds(30)
        val JITTER: Duration = Duration.ofMillis(500)
        // A source asking us to wait an hour gets a failed run, not a parked thread.
        val MAX_RETRY_AFTER: Duration = Duration.ofMinutes(5)
    }
}
