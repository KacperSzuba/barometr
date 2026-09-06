package pl.barometr.inference

/**
 * What the inference service says about itself.
 *
 * Three states rather than a boolean, because the three call for different actions and
 * a caller that cannot tell them apart will retry the one case that will never recover.
 * A service that cannot be reached is a deployment problem; a service that is reachable
 * and still loading is a matter of waiting; a service that is loaded but running its
 * fallback adapter is working perfectly and producing something that must never be
 * presented as model output.
 */
sealed interface InferenceReadiness {

    /**
     * Models are loaded and the service will answer.
     *
     * [modelVersion] is the string that must be stored beside every vector this service
     * produces: it is what makes two embedding models able to coexist while a re-embedding
     * runs, and what says which of them a stored vector belongs to.
     *
     * [generative] is false when the service is running its deterministic fallback
     * because no model key is configured. Summaries still come back, and they are not
     * model output — see the `is_generative` field the service sets on every one.
     */
    data class Ready(
        val model: String,
        val modelVersion: String,
        val dimension: Int,
        val generative: Boolean,
    ) : InferenceReadiness

    /** Reached, and reporting a component that failed to load. */
    data class NotReady(val reason: String) : InferenceReadiness

    /** Not reached at all: wrong address, refused connection, rejected key, timeout. */
    data class Unreachable(val reason: String) : InferenceReadiness
}
