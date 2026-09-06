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
        val current = whereItStands(history) ?: return null
        val closed = draft.closedOn != null
        // A stage with an end is a stage the draft is no longer at, so nothing about
        // what happens next or how long it has been waiting is this register's to say.
        val left = current.until != null
        val median = paces.medianFor(draft.initiator, current.stage)
        val expectedNext = if (closed || left) null else LegislativePath.expectedAfter(current.stage)

        return DraftStatus(
            currentStage = current.stage,
            since = current.since,
            until = current.until,
            sourceLabel = current.sourceLabel,
            expectedNext = expectedNext,
            // Only where something is expected to happen. A date on a draft that has
            // nowhere left to go would be an estimate of nothing.
            expectedNextBy = median.takeIf { expectedNext != null }?.let { current.since.plus(it) },
            hardDeadline = draft.inForceFrom?.let {
                HardDeadline(it.atStartOfDay(ZoneOffset.UTC).toInstant(), HardDeadlineKind.ENTRY_INTO_FORCE)
            },
            stalledSince = if (left) null else stalledSince(current, median, closed),
        )
    }

    /**
     * The stage that describes where the draft stands, which is not always the latest
     * one recorded.
     *
     * Normally it is: the latest stage the register put it at, with the ordinal
     * breaking the tie on a day that held three of them, which is the register's own
     * ordering and the only one there is.
     *
     * A government draft that reached the Sejm is the exception, and it is not a
     * detail. An RPL card records the whole process as one coarse period and the change
     * register dates the moves inside it; those finer stages have no end, because RPL
     * never says a draft left — it leaves by being printed in the Sejm, which is
     * [GovernmentProcessClosure]'s business and ends the coarse period alone. Taking
     * the latest stage then leaves a bill sitting in the Sejm reported as out to public
     * comment, and — once there are medians for those stages — stalled there for ever.
     * So a government process that has ended is what the draft's status *is*: the
     * register's whole record, and the day it stopped being the register that answers.
     */
    private fun whereItStands(history: List<RecordedStage>): RecordedStage? =
        history.lastOrNull { it.stage == LegislativeStage.GOVERNMENT_PROCESS && it.until != null }
            ?: history.maxWithOrNull(compareBy({ it.since }, { it.ordinal }))

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
