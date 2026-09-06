package pl.barometr.sources.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.sources.api.IngestionMode
import pl.barometr.sources.api.RunOutcome
import pl.barometr.sources.api.RunReport
import pl.barometr.sources.api.SourceId
import pl.barometr.sources.internal.jooq.tables.references.SOURCE
import pl.barometr.sources.internal.jooq.tables.references.SOURCE_RUN
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The run history — which is what the two things this system cannot otherwise see are
 * derived from: when a source is due to be read again, and whether the last read
 * brought back suspiciously little.
 *
 * Both are read from what happened rather than from a schedule anybody maintains, so
 * the queries here are load-bearing in a way no caller would notice failing: an average
 * that quietly included failed runs would drag the baseline down until an outage
 * stopped looking like one.
 */
class JooqSourceRunsTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val runs = JooqSourceRuns(dsl, JsonMapper.builder().addModule(kotlinModule()).build(), clock)

    private val sejm = SourceId(
        dsl.select(SOURCE.ID).from(SOURCE).where(SOURCE.CONNECTOR_ID.eq("sejm")).fetchSingle().value1()!!,
    )

    @BeforeEach
    fun clearHistory() {
        dsl.deleteFrom(SOURCE_RUN).execute()
    }

    @Test
    fun `a finished run records what it brought back`() {
        val runId = runs.start(sejm, IngestionMode.INCREMENTAL)
        clock.advanceBy(Duration.ofSeconds(40))

        runs.finish(
            runId,
            RunOutcome.SUCCEEDED,
            RunReport(documentsSeen = 12, documentsStored = 3, errors = 0, schemaWarnings = listOf("MISSING_FIELD:/x")),
        )

        val row = dsl.selectFrom(SOURCE_RUN).where(SOURCE_RUN.ID.eq(runId.value)).fetchSingle()
        assertEquals(RunOutcome.SUCCEEDED.wireName, row.outcome)
        assertEquals(12, row.documentsSeen)
        assertEquals(3, row.documentsStored)
        assertEquals(clock.instant(), assertNotNull(row.finishedAt).toInstant())
        assertNotNull(row.schemaWarnings, "what the source said unexpectedly is kept with the run")
    }

    /** Written even when a run fails, and especially then. */
    @Test
    fun `a failed run keeps the reason it failed`() {
        val runId = runs.start(sejm, IngestionMode.INCREMENTAL)

        runs.finish(
            runId,
            RunOutcome.FAILED,
            RunReport(
                documentsSeen = 2,
                documentsStored = 2,
                errors = 1,
                schemaWarnings = emptyList(),
                failureReason = "connection reset",
            ),
        )

        val row = dsl.selectFrom(SOURCE_RUN).where(SOURCE_RUN.ID.eq(runId.value)).fetchSingle()
        assertEquals(RunOutcome.FAILED.wireName, row.outcome)
        assertEquals("connection reset", row.failureReason)
        assertNull(row.schemaWarnings, "an empty list is not a warning to keep")
    }

    /**
     * The cadence is derived from this, rather than from a chain of self-scheduling
     * jobs: a chain would have to enqueue its successor while still running, which the
     * queue's dedup key correctly refuses, so it would stop after one run.
     */
    @Test
    fun `the last finished run of a mode is the newest of that mode alone`() {
        finishedRun(IngestionMode.INCREMENTAL, seen = 5)
        clock.advanceBy(Duration.ofMinutes(30))
        val backfill = finishedRun(IngestionMode.BACKFILL, seen = 100)

        assertEquals(backfill, runs.lastFinishedAt(sejm, IngestionMode.BACKFILL))
        assertEquals(
            backfill.minus(Duration.ofMinutes(30)),
            runs.lastFinishedAt(sejm, IngestionMode.INCREMENTAL),
            "a backfill says nothing about when the source was last polled",
        )
    }

    @Test
    fun `a source that has never finished a run has no last run`() {
        runs.start(sejm, IngestionMode.INCREMENTAL)

        assertNull(runs.lastFinishedAt(sejm, IngestionMode.INCREMENTAL), "a run that started has not finished")
    }

    @Test
    fun `the baseline is the mean of what recent runs saw`() {
        listOf(10, 20, 30).forEach {
            finishedRun(IngestionMode.INCREMENTAL, seen = it)
            clock.advanceBy(Duration.ofMinutes(15))
        }

        assertEquals(20.0, runs.recentAverageDocumentsSeen(sejm, IngestionMode.INCREMENTAL, runs = 5))
    }

    /** Averaging failures in would drag the baseline down until an outage looked normal. */
    @Test
    fun `a failed run is left out of the baseline`() {
        finishedRun(IngestionMode.INCREMENTAL, seen = 100)
        clock.advanceBy(Duration.ofMinutes(15))
        finishedRun(IngestionMode.INCREMENTAL, seen = 0, outcome = RunOutcome.FAILED)

        assertEquals(100.0, runs.recentAverageDocumentsSeen(sejm, IngestionMode.INCREMENTAL, runs = 5))
    }

    /** Only the window asked for, so a source that has changed pace is judged on its recent past. */
    @Test
    fun `the baseline looks no further back than the window`() {
        listOf(1000, 10, 20).forEach {
            finishedRun(IngestionMode.INCREMENTAL, seen = it)
            clock.advanceBy(Duration.ofMinutes(15))
        }

        assertEquals(15.0, runs.recentAverageDocumentsSeen(sejm, IngestionMode.INCREMENTAL, runs = 2))
    }

    @Test
    fun `a source with no history has no baseline to compare against`() {
        assertNull(runs.recentAverageDocumentsSeen(sejm, IngestionMode.INCREMENTAL, runs = 5))
    }

    private fun finishedRun(
        mode: IngestionMode,
        seen: Int,
        outcome: RunOutcome = RunOutcome.SUCCEEDED,
    ): java.time.Instant {
        val runId = runs.start(sejm, mode)
        runs.finish(
            runId,
            outcome,
            RunReport(documentsSeen = seen, documentsStored = seen, errors = 0, schemaWarnings = emptyList()),
        )

        return clock.instant()
    }
}
