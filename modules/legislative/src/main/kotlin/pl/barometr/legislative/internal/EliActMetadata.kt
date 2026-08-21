package pl.barometr.legislative.internal

import pl.barometr.shared.Eli
import java.time.LocalDate

/**
 * An act as its archived metadata describes it.
 *
 * [unmappedLabels] is part of the result rather than a warning swallowed inside the
 * reader: a label this system does not understand is a fact about the source that
 * belongs in a counter, and a reader that logged it privately would leave the volume
 * of them invisible.
 */
data class EliActMetadata(
    val eli: Eli,
    val title: String,
    /** The publisher's own word: `Ustawa`, `Rozporządzenie`, `Obwieszczenie`. */
    val type: String,
    /** Published in the journal — `promulgation`, not the date on the act. */
    val announcedOn: LocalDate?,
    val inForceFrom: LocalDate?,
    val prints: List<SejmPrintReference>,
    val references: List<ActReferenceEdge>,
    val unmappedLabels: List<String>,
)
