package pl.barometr.sources.internal

import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.sources.api.IngestionMode
import pl.barometr.sources.api.RunId
import pl.barometr.sources.api.RunOutcome
import pl.barometr.sources.api.RunReport
import pl.barometr.sources.api.SourceId
import pl.barometr.sources.api.SourceRuns
import pl.barometr.sources.internal.jooq.tables.references.SOURCE_RUN
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * One row per connector run — the raw material for source health monitoring.
 *
 * Written even when a run fails, and especially then: a source that answers
 * HTTP 200 with nothing is only detectable by comparing a finished run against
 * the ones before it.
 */
@Repository
@Transactional
class JooqSourceRuns(
    private val dsl: DSLContext,
    private val json: ObjectMapper,
    private val clock: Clock,
) : SourceRuns {

    override fun start(sourceId: SourceId, mode: IngestionMode): RunId {
        val runId = RunId.next()
        dsl.insertInto(SOURCE_RUN)
            .set(SOURCE_RUN.ID, runId.value)
            .set(SOURCE_RUN.SOURCE_ID, sourceId.value)
            .set(SOURCE_RUN.MODE, mode.wireName)
            .set(SOURCE_RUN.STARTED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .execute()
        return runId
    }

    override fun finish(runId: RunId, outcome: RunOutcome, report: RunReport) {
        dsl.update(SOURCE_RUN)
            .set(SOURCE_RUN.FINISHED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .set(SOURCE_RUN.OUTCOME, outcome.wireName)
            .set(SOURCE_RUN.DOCUMENTS_SEEN, report.documentsSeen)
            .set(SOURCE_RUN.DOCUMENTS_STORED, report.documentsStored)
            .set(SOURCE_RUN.ERRORS, report.errors)
            .set(
                SOURCE_RUN.SCHEMA_WARNINGS,
                report.schemaWarnings.takeIf { it.isNotEmpty() }
                    ?.let { JSONB.valueOf(json.writeValueAsString(it)) },
            )
            .set(SOURCE_RUN.FAILURE_REASON, report.failureReason)
            .where(SOURCE_RUN.ID.eq(runId.value))
            .execute()
    }

    @Transactional(readOnly = true)
    override fun lastFinishedAt(sourceId: SourceId, mode: IngestionMode): java.time.Instant? =
        dsl.select(SOURCE_RUN.FINISHED_AT)
            .from(SOURCE_RUN)
            .where(SOURCE_RUN.SOURCE_ID.eq(sourceId.value))
            .and(SOURCE_RUN.MODE.eq(mode.wireName))
            .and(SOURCE_RUN.FINISHED_AT.isNotNull)
            .orderBy(SOURCE_RUN.FINISHED_AT.desc())
            .limit(1)
            .fetchOne()
            ?.value1()
            ?.toInstant()

    @Transactional(readOnly = true)
    override fun recentAverageDocumentsSeen(
        sourceId: SourceId,
        mode: IngestionMode,
        runs: Int,
    ): Double? =
        dsl.select(SOURCE_RUN.DOCUMENTS_SEEN)
            .from(SOURCE_RUN)
            .where(SOURCE_RUN.SOURCE_ID.eq(sourceId.value))
            .and(SOURCE_RUN.MODE.eq(mode.wireName))
            // Only successful runs: averaging in failures would drag the baseline
            // down until an outage stopped looking like one.
            .and(SOURCE_RUN.OUTCOME.eq(RunOutcome.SUCCEEDED.wireName))
            .orderBy(SOURCE_RUN.FINISHED_AT.desc())
            .limit(runs)
            .fetch(SOURCE_RUN.DOCUMENTS_SEEN)
            .filterNotNull()
            .takeIf { it.isNotEmpty() }
            ?.average()
}
