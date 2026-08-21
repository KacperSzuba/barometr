package pl.barometr.legislative.api

import pl.barometr.shared.Eli
import java.time.LocalDate

/**
 * An act as other contexts see it: what it is called, when it was published, and when
 * it starts applying.
 *
 * A value type, never a row. Search indexes these; nothing outside legislative reads
 * the table they come from, so the columns stay free to change.
 */
data class PublishedAct(
    val id: ActId,
    val eli: Eli,
    val title: String,
    /** The publisher's own word: `Ustawa`, `Rozporządzenie`, `Obwieszczenie`. */
    val type: String,
    val publisher: String,
    val announcedOn: LocalDate?,
    val inForceFrom: LocalDate?,
)
