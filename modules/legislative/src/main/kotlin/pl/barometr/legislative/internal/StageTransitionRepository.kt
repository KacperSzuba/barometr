package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.jooq.postgres.extensions.types.OffsetDateTimeRange
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.STAGE_TRANSITION
import pl.barometr.legislative.internal.jooq.tables.references.STAGE_TRANSITION_LATEST
import pl.barometr.shared.Ids
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The history of where a draft has been. SQL only, and append-only.
 *
 * Nothing here updates or deletes. A stage whose period turns out to be different —
 * because the next stage has since arrived and closed it — is recorded again as a new
 * fact with a later `known_at`, beside the one it corrects. That is what the schema's
 * two time axes are for, and it is why "what did we believe on Tuesday about Monday"
 * has an answer.
 *
 * Reading is the other half of that bargain, and it goes through
 * `stage_transition_latest`: what stands now is one statement per fact, and which one
 * that is is settled in the view rather than by each reader in turn.
 */
@Repository
class StageTransitionRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * Records every fact not already held, and reports how many were new.
     *
     * `DO NOTHING` against the unique index on (draft, stage, period): a process is
     * re-read every time it moves, restating its whole history each time, and
     * appending all of it again would bury the two rows that changed.
     */
    @Transactional
    fun recordFacts(
        draftId: DraftId,
        facts: List<StageFact>,
        statedBy: DocumentVersionId,
        knownAt: Instant,
    ): Int {
        if (facts.isEmpty()) return 0

        val recordedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val known = OffsetDateTime.ofInstant(knownAt, ZoneOffset.UTC)

        return dsl.batch(
            facts.map { fact ->
                dsl.insertInto(STAGE_TRANSITION)
                    .set(STAGE_TRANSITION.ID, Ids.next())
                    .set(STAGE_TRANSITION.DRAFT_ID, draftId.value)
                    .set(STAGE_TRANSITION.STAGE, fact.stage.wireName)
                    .set(STAGE_TRANSITION.VALID_PERIOD, periodOf(fact))
                    .set(STAGE_TRANSITION.KNOWN_AT, known)
                    .set(STAGE_TRANSITION.SOURCE_DOCUMENT_VERSION_ID, statedBy.value)
                    .set(STAGE_TRANSITION.ORDINAL, fact.ordinal)
                    .set(STAGE_TRANSITION.SOURCE_LABEL, fact.sourceLabel)
                    .set(STAGE_TRANSITION.IS_EXCEPTION, fact.isException)
                    .set(STAGE_TRANSITION.CREATED_AT, recordedAt)
                    .onConflictDoNothing()
            },
        ).execute().count { it > 0 }
    }

    /**
     * A draft's history as it stands, oldest first.
     *
     * The statements a later reading corrected are left behind in the table rather
     * than returned: a first reading read twice — once while it was the last stage the
     * register knew, once after the committee had the draft — is one stage that ended,
     * not one stage that ended and one that never did. [RecordedStage] carries no
     * `known_at`, so a caller handed both could not have told which was which.
     *
     * Ordering is restored here because the view's own is dictated by what it
     * deduplicates on; a draft's history is a handful of rows, and the order a reader
     * wants is by when the stage began.
     */
    @Transactional(readOnly = true)
    fun historyOf(draftId: DraftId): List<RecordedStage> =
        dsl.selectFrom(STAGE_TRANSITION_LATEST)
            .where(STAGE_TRANSITION_LATEST.DRAFT_ID.eq(draftId.value))
            .fetch { record ->
                RecordedStage(
                    stage = LegislativeStage.of(record.stage!!) ?: LegislativeStage.UNKNOWN,
                    since = record.validFrom!!.toInstant(),
                    until = record.validTo?.toInstant(),
                    ordinal = record.ordinal!!,
                    sourceLabel = record.sourceLabel,
                    isException = record.isException!!,
                )
            }
            .sortedWith(compareBy({ it.since }, { it.ordinal }))

    /** `[from, until)`, with an open end while the draft is still there. */
    private fun periodOf(fact: StageFact): OffsetDateTimeRange = OffsetDateTimeRange.offsetDateTimeRange(
        OffsetDateTime.ofInstant(fact.from, ZoneOffset.UTC),
        fact.until?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
    )
}
