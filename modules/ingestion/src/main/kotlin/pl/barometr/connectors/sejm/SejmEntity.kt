package pl.barometr.connectors.sejm

import tools.jackson.databind.JsonNode

/**
 * One entity exactly as the Sejm API returned it.
 *
 * The body stays wrapped: callers get the key they need and hand the whole thing
 * to a canonicaliser, so nothing outside this file navigates a `JsonNode`.
 */
class SejmEntity internal constructor(
    /** The source's own identifier: a print number, a club symbol, an MP id. */
    val naturalKey: String,
    internal val body: JsonNode,
)
