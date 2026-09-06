package pl.barometr.inference

/**
 * The single door to the inference service.
 *
 * Nothing else in this application may open an HTTP connection to it. The reason is the
 * same one that gave the connectors a single client: the key, the client id, the timeouts
 * and the two error shapes the service can return are decisions that have to be made once.
 *
 * Deliberately narrow for now — asking whether the service is there is the only thing any
 * caller needs before the embedding pipeline exists. Every capability added later
 * (embeddings, the cost cascade, classification) is a method here, not a second client.
 */
interface InferenceClient {

    /**
     * Asks the service about itself. Never throws: every failure is one of the three
     * [InferenceReadiness] states, because a health probe that throws is a health probe
     * that reports nothing.
     */
    fun readiness(): InferenceReadiness
}
