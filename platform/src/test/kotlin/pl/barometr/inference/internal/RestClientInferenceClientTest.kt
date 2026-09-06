package pl.barometr.inference.internal

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpResponse
import org.springframework.web.client.RestClient
import pl.barometr.inference.InferenceReadiness
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Reading the inference service's answer about itself.
 *
 * Three states, three different things a deployment does about them, and the reason each
 * is asserted here rather than assumed: the service answers `503` with the *same body* it
 * answers `200` with, so a client that read the status instead of the body would report a
 * loading model as an unreachable service and send somebody to check the network.
 *
 * Answered by an interceptor rather than a stub server: what is under test is the reading
 * of a payload, and a real socket would only make it harder to see.
 */
class RestClientInferenceClientTest {

    @Test
    fun `a loaded service reports its model and the version its vectors carry`() {
        val readiness = clientAnswering(
            status = 200,
            body = """
                {"ready": true, "environment": "development", "components": {
                  "embedder": {"ready": true, "model": "intfloat/multilingual-e5-large",
                               "model_version": "multilingual-e5-large@v1", "dimension": 1024},
                  "llm": {"ready": true, "model": "claude-opus-5",
                          "model_version": "claude-opus-5", "generative": true}}}
            """.trimIndent(),
        ).readiness()

        val ready = assertIs<InferenceReadiness.Ready>(readiness)
        assertEquals("intfloat/multilingual-e5-large", ready.model)
        assertEquals("multilingual-e5-large@v1", ready.modelVersion)
        assertEquals(1024, ready.dimension)
    }

    /**
     * The fallback adapter is not a failure. The service is answering and says so on every
     * response it produces; what must not happen is a deployment reading it as an outage.
     */
    @Test
    fun `a service running its fallback adapter is ready, and says it is not generative`() {
        val readiness = clientAnswering(
            status = 200,
            body = """
                {"ready": true, "environment": "development", "components": {
                  "embedder": {"ready": true, "model": "e5", "model_version": "e5@v1", "dimension": 1024},
                  "llm": {"ready": true, "model": "heuristic", "model_version": "heuristic-v0",
                          "generative": false, "note": "Adapter zastępczy"}}}
            """.trimIndent(),
        ).readiness()

        val ready = assertIs<InferenceReadiness.Ready>(readiness)
        assertEquals(false, ready.generative)
    }

    @Test
    fun `a component that failed to load is named, not just counted`() {
        val readiness = clientAnswering(
            status = 503,
            body = """
                {"ready": false, "environment": "development", "components": {
                  "embedder": {"ready": false, "error": "ONNX model not found"},
                  "llm": {"ready": true, "model": "heuristic", "model_version": "heuristic-v0",
                          "generative": false}}}
            """.trimIndent(),
        ).readiness()

        val notReady = assertIs<InferenceReadiness.NotReady>(readiness)
        assertContains(notReady.reason, "embedder")
        assertContains(notReady.reason, "ONNX model not found")
    }

    @Test
    fun `a service that cannot be reached is unreachable rather than an exception`() {
        val restClient = RestClient.builder()
            .requestInterceptor { _, _, _ -> throw IOException("Connection refused") }
            .build()

        val readiness = RestClientInferenceClient(restClient).readiness()

        val unreachable = assertIs<InferenceReadiness.Unreachable>(readiness)
        assertContains(unreachable.reason, "Connection refused")
    }

    /**
     * A rejected key must never read as a loading model.
     *
     * The refusal body has no `ready` field, so a client that read every status as a
     * readiness payload would call it "not ready" — and somebody would wait for models to
     * finish loading that were loaded all along, while this application's own
     * `app.ai.api-key` was the thing that was wrong.
     */
    @Test
    fun `a rejected key is unreachable, not a service that is still loading`() {
        val readiness = clientAnswering(
            status = 401,
            body = """{"error": "UNAUTHORIZED", "message": "Nieprawid\u0142owy nag\u0142\u00f3wek X-Api-Key.", "details": {}}""",
        ).readiness()

        val unreachable = assertIs<InferenceReadiness.Unreachable>(readiness)
        assertContains(unreachable.reason, "401")
    }

    private fun clientAnswering(status: Int, body: String): RestClientInferenceClient {
        val restClient = RestClient.builder()
            .requestInterceptor { _, _, _ -> CannedJson(status, body) }
            .build()

        return RestClientInferenceClient(restClient)
    }

    private class CannedJson(private val status: Int, body: String) : ClientHttpResponse {
        private val bytes = body.toByteArray()

        override fun getStatusCode(): HttpStatusCode = HttpStatusCode.valueOf(status)

        override fun getStatusText(): String = "canned"

        override fun getHeaders(): HttpHeaders = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            contentLength = bytes.size.toLong()
        }

        override fun getBody(): InputStream = ByteArrayInputStream(bytes)

        override fun close() = Unit
    }
}
