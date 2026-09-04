package pl.barometr.alerts.internal

import org.springframework.stereotype.Component
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.ConsultationCalendar
import pl.barometr.legislative.api.ConsultationDeadline
import pl.barometr.legislative.api.ConsultationId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeCatalog
import pl.barometr.legislative.api.LegislativeKind
import pl.barometr.legislative.api.LegislativeSignals
import pl.barometr.shared.WorkingDays
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Reads a buffered identity back into the thing it names.
 *
 * At judgement time rather than at arrival, so a draft that moved twice while the
 * buffer waited is judged on where it stands now — and so nothing here holds a second,
 * ageing copy of what legislative already knows. Which is also why the ranking signals
 * are read here: a draft's position on the path is exactly the sort of fact that would
 * be stale by the time the window closed.
 *
 * A consultation is read the same way and for a sharper version of the same reason: a
 * ministry can extend one between the watch seeing it and the run judging it, and a
 * date read at the wrong end of that gap is the one thing this alert must never get
 * wrong.
 */
@Component
class BufferedItemReader(
    private val catalog: LegislativeCatalog,
    private val consultations: ConsultationCalendar,
    private val clock: Clock,
) {

    /** Null when the thing is gone or was never derived — an item nobody can be told about. */
    fun read(item: PendingItem): ResolvedItem? {
        val id = runCatching { UUID.fromString(item.subjectId) }.getOrNull() ?: return null

        return when (item.kind) {
            LegislativeKind.ACT -> catalog.actById(ActId(id))?.let {
                ResolvedItem(
                    kind = LegislativeKind.ACT,
                    id = item.subjectId,
                    title = it.title,
                    eli = it.eli.value,
                    stage = null,
                    // Derived from the act in hand rather than queried again: an act
                    // is at the end of the path by definition, and the only other
                    // thing ranking wants is a date this row already carries.
                    signals = LegislativeSignals.of(it),
                )
            }

            ConsultationNotice.KIND -> consultations.consultationById(ConsultationId(id))?.let(::closing)

            else -> catalog.draftById(DraftId(id))?.let {
                ResolvedItem(
                    kind = LegislativeKind.DRAFT,
                    id = item.subjectId,
                    title = it.title,
                    eli = null,
                    stage = it.currentStage,
                    signals = catalog.signalsForDraft(DraftId(id)),
                )
            }
        }
    }

    /**
     * A consultation about to close, as the draft it is about plus the day it ends.
     *
     * The draft is what the item is matched and named by: somebody watches a bill, or a
     * word in its title, and a notification that named a consultation identifier would
     * be telling them about something they never subscribed to.
     *
     * The stage is the draft's own, so a rule narrowed to the later stages leaves this
     * out along with everything else about the draft. Quietly overriding somebody's
     * filter because this alert is the useful one would be making a promise on their
     * behalf, which is the same reason urgency is a person's choice rather than a score.
     */
    private fun closing(deadline: ConsultationDeadline): ResolvedItem? {
        val draft = catalog.draftById(deadline.draftId) ?: return null

        // Which warning this is, decided here rather than carried from the watch: the
        // buffer holds an identity and nothing else, and a band worked out four hours
        // ago could name the fortnight's warning for a consultation that has since been
        // extended by a month. Null means it is no longer near, and there is nothing to
        // say until it is again.
        val warnedAt = ConsultationWarnings
            .bandFor(WorkingDays.between(LocalDate.now(clock), deadline.closesOn))
            ?: return null

        return ResolvedItem(
            kind = LegislativeKind.DRAFT,
            id = deadline.draftId.value.toString(),
            title = draft.title,
            eli = null,
            stage = draft.currentStage,
            signals = LegislativeSignals(
                progress = catalog.signalsForDraft(deadline.draftId)?.progress ?: 0.0,
                // The consultation's own date rather than whatever hard deadline the
                // draft otherwise carries: both are dates somebody else fixed, and this
                // is the one the item is about and the nearer of the two.
                hardDeadlineOn = deadline.closesOn.atStartOfDay(ZoneOffset.UTC).toInstant(),
            ),
            notice = ConsultationNotice(deadline.id.value.toString(), deadline.closesOn, warnedAt),
        )
    }
}
