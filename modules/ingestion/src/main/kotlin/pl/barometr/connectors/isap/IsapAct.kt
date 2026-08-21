package pl.barometr.connectors.isap

import pl.barometr.shared.Eli
import tools.jackson.databind.JsonNode
import java.time.LocalDateTime

/**
 * One act exactly as the listing returned it.
 *
 * The body stays wrapped: the connector takes the address it archives under and the
 * stamp it compares against a cursor, then hands the rest to the canonicaliser, so
 * nothing outside this package navigates a `JsonNode`. Everything else the act says
 * — dates, references, print numbers — is read later out of the archive, by the
 * context that needs it. Reading it here would put the same parsing in two places
 * and make re-deriving from the archive depend on the connector.
 */
class IsapAct internal constructor(
    val eli: Eli,
    /**
     * `changeDate`: when ISAP last touched this record. Not when the act changed —
     * a bulk re-index moves it too, which is why it is only ever used to decide
     * whether a pass is worth continuing, never as a fact about the act.
     */
    val changedAt: LocalDateTime?,
    internal val body: JsonNode,
)
