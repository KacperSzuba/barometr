package pl.barometr.legislative.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.barometr.legislative.api.DraftId
import java.time.Clock
import java.time.ZoneOffset

/**
 * Ends the government's process on the day the Sejm printed the draft.
 *
 * An RPL card never states that a draft left: it leaves by arriving in the Sejm, which
 * is a different register saying so — which is why [RclCardProjector] opens the period
 * and nothing on that source can ever close it. The join between the two registers is
 * the moment the other register's word becomes available, so the correction is made
 * there and nowhere else.
 *
 * Two things this deliberately does not touch. The finer stages a change register
 * dates — agreement, consultation, the standing committee — are that register's own
 * statements about moves inside the process, and rewriting them from the Sejm's dates
 * would be one source correcting another's facts rather than adding its own. And
 * nothing is updated: the closure is a new fact beside the open one it corrects, which
 * is what `stage_transition` is for and what `stage_transition_latest` then resolves.
 *
 * What it buys, beyond a timeline that stops claiming a draft is still out to comment:
 * the government stage becomes a *completed* stay, so how long the government's own
 * process takes becomes measurable at all — [StagePaceRepository] counts closed
 * periods only, and until now not one of these ever closed.
 */
@Service
class GovernmentProcessClosure(
    private val drafts: DraftRepository,
    private val transitions: StageTransitionRepository,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun closeOnArrivalInSejm(governmentDraftId: DraftId, sejmDraftId: DraftId) {
        val arrival = drafts.summaryOf(sejmDraftId)?.startedOn ?: return skip("undated-arrival")
        val open = transitions.historyOf(governmentDraftId)
            .lastOrNull { it.stage == LegislativeStage.GOVERNMENT_PROCESS && it.until == null }
            ?: return skip("nothing-open")

        val arrivedAt = arrival.atStartOfDay(ZoneOffset.UTC).toInstant()
        // A print that starts before the card that produced it is one of the two
        // registers being wrong, and an empty period is the one thing the schema
        // refuses outright. Counted rather than corrected: which of them is wrong is
        // not answerable from here.
        if (!arrivedAt.isAfter(open.since)) return skip("arrival-before-start")

        // The Sejm's own process document, which is what states the arrival. Without
        // it the correction would carry no evidence, and a fact about a draft with
        // nothing behind it is worse than the open period it replaces.
        val statedBy = transitions.firstStatedBy(sejmDraftId) ?: return skip("no-provenance")

        val recorded = transitions.recordFacts(
            draftId = governmentDraftId,
            facts = listOf(
                StageFact(
                    stage = LegislativeStage.GOVERNMENT_PROCESS,
                    from = open.since,
                    until = arrivedAt,
                    ordinal = open.ordinal,
                    // The card's own word for where the draft had got to, kept so the
                    // correction reads as the same fact restated and not a new one. A
                    // stage recorded without one is a card that marked nothing as
                    // current; naming the process itself is what the projector does in
                    // the same case.
                    sourceLabel = open.sourceLabel ?: PROCESS_LABEL,
                    isException = open.isException,
                ),
            ),
            statedBy = statedBy,
            knownAt = clock.instant(),
        )

        if (recorded > 0) {
            meters.counter("legislative.government_process.closure", "outcome", "closed").increment()
            log.debug("Government process of draft {} ended on {}", governmentDraftId, arrival)
        }
    }

    private fun skip(reason: String) =
        meters.counter("legislative.government_process.closure", "outcome", reason).increment()

    private companion object {
        /** The same words [RclCardProjector] uses when a card marks no stage as current. */
        const val PROCESS_LABEL = "Rządowy proces legislacyjny"
    }
}
