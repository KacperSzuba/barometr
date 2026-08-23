package pl.barometr.legislative.api

import java.time.Instant
import java.time.ZoneOffset

/**
 * What legislative knows about how much a matter matters.
 *
 * Not a score. Whoever is ranking has other signals this context cannot see — whose
 * profile caught it, and how narrowly — so what crosses the boundary is the evidence
 * rather than the verdict. That split is also what keeps the ranking explainable:
 * a number arriving from another context could be shown to a reader but not
 * accounted for.
 *
 * [progress] is the one thing only this context can answer, because only this context
 * knows the path: how far along it a matter has travelled, from 0 where the
 * government's process begins to 1 for a law that has been published. The
 * specification's first-named signal is exactly this — closer to enactment is more
 * important — and it is a position rather than a probability.
 */
data class LegislativeSignals(
    val progress: Double,
    /**
     * A date somebody else fixed, never one this system estimated. The two are
     * separate columns in the schema and separate fields all the way out to the API,
     * and a ranking that treated a median-based guess as a statutory deadline would
     * be the failure that separation exists to prevent.
     */
    val hardDeadlineOn: Instant?,
) {
    init {
        require(progress in 0.0..1.0) { "Progress along the path is a fraction, got $progress" }
    }

    companion object {
        /**
         * An act has arrived: there is no further along to be.
         *
         * Read from the act in hand rather than fetched again, which is why this is a
         * factory here rather than a second method on the catalog — the caller that
         * needs it has just loaded the row it would query.
         */
        fun of(act: PublishedAct): LegislativeSignals = LegislativeSignals(
            progress = 1.0,
            hardDeadlineOn = act.inForceFrom?.atStartOfDay(ZoneOffset.UTC)?.toInstant(),
        )
    }
}
