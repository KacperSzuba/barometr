package pl.barometr.legislative.internal

import pl.barometr.legislative.api.ActId
import pl.barometr.shared.Eli
import java.time.LocalDate

/**
 * One act naming another, and what it does to it.
 *
 * [act] and [title] are null when the archive does not hold the other side. That is
 * ordinary rather than exceptional — a 2026 amendment names statutes from decades this
 * ingestion never reached — and the address alone is still the thing a reader
 * recognises and can look up.
 */
data class ActCitation(
    val eli: Eli,
    val relation: ActRelation,
    val act: ActId?,
    val title: String?,
    val announcedOn: LocalDate?,
)
