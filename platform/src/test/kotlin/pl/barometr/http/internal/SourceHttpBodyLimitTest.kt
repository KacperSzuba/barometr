package pl.barometr.http.internal

import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpResponse
import org.springframework.web.client.RestClient
import pl.barometr.http.HttpFetch
import pl.barometr.http.HttpOutcome
import pl.barometr.http.HttpPolicy
import pl.barometr.http.RobotsPolicy
import pl.barometr.testing.TestClock
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The ceiling on a downloaded body.
 *
 * The whole body is buffered so that it can be hashed, which is what content
 * addressing needs — so without a ceiling one mislabelled link is an out-of-memory
 * error in the process holding the archive's only writer. It mattered little while
 * every source served HTML pages and matters from the moment a connector follows a
 * link to a file.
 *
 * What is actually asserted is the *before*: an oversized response is turned away
 * without its body ever being read, because refusing afterwards would already have
 * spent the memory the limit exists to protect.
 */
class SourceHttpBodyLimitTest {

    @Test
    fun `a body larger than the limit is refused, and never read`() {
        val response = CannedResponse(body = ByteArray(5_000))

        val outcome = clientAllowing(200, response).fetch(HttpFetch(URI.create(TARGET)))

        val failed = assertIs<HttpOutcome.Failed>(outcome)
        assertContains(failed.detail, "5000")
        assertContains(failed.detail, "200")
        assertFalse(response.bodyWasRead, "the body must be refused before it is buffered")
    }

    @Test
    fun `a body within the limit is fetched as usual`() {
        val response = CannedResponse(body = "Ustawa o cenach energii".toByteArray())

        val outcome = clientAllowing(5_000, response).fetch(HttpFetch(URI.create(TARGET)))

        val fetched = assertIs<HttpOutcome.Fetched>(outcome)
        assertEquals("Ustawa o cenach energii", String(fetched.body))
        assertTrue(response.bodyWasRead)
    }

    /**
     * A source that declines to state its length is downloaded whichever size it
     * turns out to be. Refusing it afterwards would cost the memory the check exists
     * to protect and buy nothing back — so the limit is what a source *declares*, and
     * this pins that reading rather than leaving it to be discovered.
     */
    @Test
    fun `a body of undeclared length is fetched, limit or no limit`() {
        val response = CannedResponse(body = ByteArray(5_000), declareLength = false)

        val outcome = clientAllowing(200, response).fetch(HttpFetch(URI.create(TARGET)))

        assertIs<HttpOutcome.Fetched>(outcome)
    }

    private fun clientAllowing(maxBodyBytes: Long, response: CannedResponse): RestClientSourceHttpClient {
        // Answered by an interceptor rather than by a stub server: what needs
        // controlling is one header and whether the body was ever touched, and a real
        // socket would only make both harder to see.
        val restClient = RestClient.builder()
            .requestInterceptor { request, _, _ ->
                if (request.uri.path == "/robots.txt") NoRobotsFile else response
            }
            .build()

        return RestClientSourceHttpClient(
            restClient = restClient,
            policy = HttpPolicy(
                requestsPerSecond = 1_000.0,
                maxBodyBytes = maxBodyBytes,
                robots = RobotsPolicy.Exempt("Test, and the gate has its own suite."),
            ),
            rateLimiters = HostRateLimiters(RateLimiterRegistry.ofDefaults()),
            robots = RobotsGate(restClient, TestClock()),
        )
    }

    /** A response whose body reports whether anybody asked for it. */
    private class CannedResponse(
        private val body: ByteArray,
        private val declareLength: Boolean = true,
    ) : ClientHttpResponse {
        var bodyWasRead = false
            private set

        override fun getStatusCode(): HttpStatusCode = HttpStatusCode.valueOf(200)

        override fun getStatusText(): String = "OK"

        override fun getHeaders(): HttpHeaders = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_PDF
            if (declareLength) contentLength = body.size.toLong()
        }

        override fun getBody(): InputStream {
            bodyWasRead = true
            return ByteArrayInputStream(body)
        }

        override fun close() = Unit
    }

    /** 404 for robots.txt, which crawler-commons reads as "nothing is forbidden". */
    private object NoRobotsFile : ClientHttpResponse {
        override fun getStatusCode(): HttpStatusCode = HttpStatusCode.valueOf(404)
        override fun getStatusText(): String = "Not Found"
        override fun getHeaders(): HttpHeaders = HttpHeaders()
        override fun getBody(): InputStream = InputStream.nullInputStream()
        override fun close() = Unit
    }

    private companion object {
        const val TARGET = "https://legislacja.rcl.gov.pl/docs//1/1/2/3/dokument1.pdf"
    }
}
