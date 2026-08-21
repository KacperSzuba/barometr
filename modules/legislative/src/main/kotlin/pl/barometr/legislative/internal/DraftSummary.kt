package pl.barometr.legislative.internal

import pl.barometr.legislative.api.DraftId
import java.time.LocalDate

/**
 * What deciding a draft's status needs to know about the draft itself.
 *
 * [inForceFrom] comes from the act the draft became, which is the only hard date in
 * the whole picture — everything else about the future is an estimate, and the two are
 * kept apart from here to the API.
 */
data class DraftSummary(
    val id: DraftId,
    val title: String,
    val initiator: DraftInitiator,
    val term: Int?,
    val startedOn: LocalDate?,
    val closedOn: LocalDate?,
    val outcome: DraftOutcome?,
    val inForceFrom: LocalDate?,
)
