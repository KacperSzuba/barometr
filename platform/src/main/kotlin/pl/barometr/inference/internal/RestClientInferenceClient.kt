package pl.barometr.inference.internal

import org.springframework.http.HttpStatusCode
import org.springframework.web.client.RestClient
import pl.barometr.inference.InferenceClient
import pl.barometr.inference.InferenceReadiness
import tools.jackson.databind.JsonNode

/**
 * Talks to the inference service over HTTP.
 *
 * The `RestClient` arrives fully configured — base URL, both headers, timeouts — so this
 * class holds none of that. What is left is the one thing that is genuinely this
 * service's own: turning its answer into the three states a caller can act on.
 *
 * `exchange` rather than `retrieve`, because the readiness payload arrives under two
 * statuses: `200` when the models are loaded and `503` when one failed, with the same
 * body either way and the reason inside it. `retrieve` would throw the `503` away before
 * anything could read that reason.
 *
 * Any other status is a different conversation — a rejected key, a wrong path, a proxy
 * answering instead of the service — and none of them says anything about whether models
 * are loaded. Reading them as a readiness payload would report a closed door as a loading
 * model and send whoever is on call to wait for something that is never going to happen.
 */
class RestClientInferenceClient(private val restClient: RestClient) : InferenceClient {

    override fun readiness(): InferenceReadiness =
        try {
            restClient.get()
                .uri(READINESS_PATH)
                .exchange { _, response ->
                    val status = response.statusCode
                    if (status.value() in READINESS_STATUSES) {
                        readinessFrom(response.bodyTo(JsonNode::class.java))
                    } else {
                        InferenceReadiness.Unreachable(refusalOf(status))
                    }
                }
        } catch (failure: Exception) {
            // Deliberately broad, and the one place in this class that is. A refused
            // connection, a timeout and a body that is not JSON all mean the same thing
            // to a caller — the service did not answer — and a probe that threw instead
            // of saying so would take the health endpoint down with it.
            InferenceReadiness.Unreachable(failure.message ?: failure.javaClass.simpleName)
        }

    private fun readinessFrom(body: JsonNode?): InferenceReadiness {
        if (body == null) return InferenceReadiness.Unreachable("empty readiness body")

        val embedder = body.path("components").path("embedder")
        val llm = body.path("components").path("llm")

        if (!body.path("ready").asBoolean(false)) {
            return InferenceReadiness.NotReady(unreadyReason(embedder, llm))
        }

        return InferenceReadiness.Ready(
            model = embedder.path("model").asString(""),
            modelVersion = embedder.path("model_version").asString(""),
            dimension = embedder.path("dimension").asInt(0),
            generative = llm.path("generative").asBoolean(false),
        )
    }

    /**
     * The service reports each component separately, and a probe that only said "not
     * ready" would send whoever is on call to read its logs to learn which one.
     */
    private fun unreadyReason(embedder: JsonNode, llm: JsonNode): String =
        listOfNotNull(
            embedder.path("error").asString("").ifBlank { null }?.let { "embedder: $it" },
            llm.path("error").asString("").ifBlank { null }?.let { "llm: $it" },
        ).joinToString("; ").ifBlank { "the service reports itself not ready" }

    /**
     * The status and nothing from the body. `401` here is almost always this
     * application's own `app.ai.api-key`, and the body would carry the service's phrasing
     * of a misconfiguration this side owns.
     */
    private fun refusalOf(status: HttpStatusCode): String =
        "the service answered ${status.value()} to the readiness probe"

    private companion object {
        const val READINESS_PATH = "/v1/ready"

        /** The two the service answers `/v1/ready` with: loaded, and a component that failed. */
        val READINESS_STATUSES = setOf(200, 503)
    }
}
