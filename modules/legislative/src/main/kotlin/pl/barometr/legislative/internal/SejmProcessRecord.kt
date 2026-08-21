package pl.barometr.legislative.internal

import pl.barometr.shared.Eli
import java.time.LocalDate

/**
 * A legislative process as its archived record describes it.
 *
 * Not every process is a draft: of five hundred in one term, barely three hundred are
 * bills or resolutions and the rest are motions, lists of candidates and government
 * information. They are all archived — that is what an archive is — and [isDraft] is
 * where that distinction is drawn once, in the vocabulary of the source that makes it.
 */
data class SejmProcessRecord(
    /** The print number, which is also how the Sejm addresses the process itself. */
    val printNumber: String,
    val term: Int,
    val title: String,
    val initiator: DraftInitiator,
    val isDraft: Boolean,
    /** Present once the act has been published; the register attaches it then. */
    val eli: Eli?,
    /** The Council of Ministers' number for the same draft in RPL, e.g. `RM-0610-102-23`. */
    val rclNumber: String?,
    val closedOn: LocalDate?,
    val outcome: DraftOutcome?,
    val stages: List<SejmProcessStage>,
)
