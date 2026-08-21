package pl.barometr.legislative.internal

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The three questions a user comes with: where is this draft, what happens next, and
 * when.
 *
 * Deliberately arithmetic rather than a model. The estimate is the median stay at the
 * current stage for drafts of this kind, added to the day the draft arrived there —
 * a base rate anybody can check and this system can explain, which the specification
 * asks for in as many words. Anything cleverer would be harder to defend and no more
 * accurate on a corpus this size.
 *
 * Pure: it takes what it is given and returns an answer. What reads the history and
 * what stores the answer are somebody else's business, which is what lets the same
 * calculation serve a card rendered now and a read model rebuilt hourly.
 */
@Component
class DraftStatusEngine(private val clock: Clock) {

    /** Null when nothing is recorded about where the draft has been. */
    fun statusOf(draft: DraftSummary, history: List<RecordedStage>, paces: StagePaces): DraftStatus? {
        // The latest stage the register put it at. Ordinal breaks the tie on a day
        // that held three of them, which is the register's own ordering and the only
        // one there is.
        val current = history.maxWithOrNull(compareBy({ it.since }, { it.ordinal })) ?: return null
        val closed = draft.closedOn != null
        val median = paces.medianFor(draft.initiator, current.stage)
        val expectedNext = if (closed) null else LegislativePath.expectedAfter(current.stage)

        return DraftStatus(
            currentStage = current.stage,
            since = current.since,
            sourceLabel = current.sourceLabel,
            expectedNext = expectedNext,
            // Only where something is expected to happen. A date on a draft that has
            // nowhere left to go would be an estimate of nothing.
            expectedNextBy = median.takeIf { expectedNext != null }?.let { current.since.plus(it) },
            hardDeadline = draft.inForceFrom?.let {
                HardDeadline(it.atStartOfDay(ZoneOffset.UTC).toInstant(), HardDeadlineKind.ENTRY_INTO_FORCE)
            },
            stalledSince = stalledSince(current, median, closed),
        )
    }

    /**
     * Twice the usual stay, and not a day earlier.
     *
     * A closed draft is never stalled — it is finished, and reporting a bill enacted
     * last winter as stuck would make the flag worthless. Nor is a draft at a stage the
     * archive cannot yet measure: with no median there is nothing to be twice of, and
     * a default would be this system inventing the very number it refused to model.
     */
    private fun stalledSince(
        current: RecordedStage,
        median: Duration?,
        closed: Boolean,
    ): Instant? {
        if (closed || median == null) return null

        val overdue = current.since.plus(median.multipliedBy(STALLED_AFTER_MEDIANS))

        return overdue.takeIf { it.isBefore(clock.instant()) }
    }

    private companion object {
        const val STALLED_AFTER_MEDIANS = 2L
    }
}
