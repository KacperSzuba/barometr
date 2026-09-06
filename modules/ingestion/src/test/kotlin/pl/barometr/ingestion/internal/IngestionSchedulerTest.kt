package pl.barometr.ingestion.internal

import org.junit.jupiter.api.Test
import pl.barometr.ingestion.api.Cursor
import pl.barometr.shared.Ids
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.IngestionMode
import pl.barometr.sources.api.RunOutcome
import pl.barometr.sources.api.RunReport
import pl.barometr.sources.api.SourceDefinition
import pl.barometr.sources.api.SourceId
import pl.barometr.sources.api.SourceRegistry
import pl.barometr.testing.TestClock
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * When a source is due, and what it means to resume a backfill.
 *
 * Both answers are derived from state rather than from jobs that schedule their own
 * successors, and that is the design being tested: a self-chaining job has to enqueue
 * its replacement while it is still running, which the dedup key correctly refuses, so
 * a chain stops after one run and nothing says so.
 */
class IngestionSchedulerTest {

    private val clock = TestClock()
    private val runs = SpyingRuns()
    private val cursors = InMemoryCursors()
    private val queue = RecordingJobQueue()

    private val sejm = SourceDefinition(
        id = SourceId(Ids.next()),
        connectorId = ConnectorId("sejm"),
        name = "API Sejmu",
        baseUrl = URI.create("https://api.sejm.gov.pl"),
        refreshInterval = Duration.ofMinutes(15),
        expectedMinRecordsPerRun = null,
    )

    private val scheduler = IngestionScheduler(
        sources = OneSource(sejm),
        runs = runs,
        cursors = cursors,
        runQueue = IngestionRunQueue(queue, JsonMapper.builder().addModule(kotlinModule()).build()),
        clock = clock,
    )

    @Test
    fun `a source that has never been read is due at once`() {
        scheduler.dispatchDueSources()

        assertEquals(listOf("ingestion:sejm:incremental"), queue.dedupKeys)
    }

    @Test
    fun `a source read inside its interval is left alone`() {
        runs.lastFinished = clock.instant()
        clock.advanceBy(Duration.ofMinutes(14))

        scheduler.dispatchDueSources()

        assertTrue(queue.queued.isEmpty(), "the source asked to be read every quarter of an hour")
    }

    @Test
    fun `a source whose interval has passed is queued again`() {
        runs.lastFinished = clock.instant()
        clock.advanceBy(Duration.ofMinutes(15))

        scheduler.dispatchDueSources()

        assertEquals(1, queue.queued.size)
    }

    /**
     * The half a chained job cannot do: a backfill reads in bounded chunks, so
     * something has to keep asking for the next one, and the recorded position is what
     * says whether there is one.
     */
    @Test
    fun `an unfinished backfill partition is brought back for its next chunk`() {
        cursors.save(sejm.id, IngestionMode.BACKFILL, mapOf("offset" to "500"), partition = "term10")

        scheduler.dispatchDueSources()

        assertTrue(
            "ingestion:sejm:backfill:term10" in queue.dedupKeys,
            "a partition with a position left in it has work left in it",
        )
    }

    @Test
    fun `a partition that has been read to the end is not resumed`() {
        cursors.save(
            sejm.id,
            IngestionMode.BACKFILL,
            mapOf(Cursor.PARTITION_DONE to "true"),
            partition = "term9",
        )

        scheduler.dispatchDueSources()

        assertTrue(queue.dedupKeys.none { it?.contains("term9") == true }, "a finished term is finished")
    }

    /**
     * Every partition can be in flight at once, which is what the partition being part
     * of the dedup key buys: one key for all of them would let one term's chunk block
     * every other term's.
     */
    @Test
    fun `each partition is queued under a key of its own`() {
        cursors.save(sejm.id, IngestionMode.BACKFILL, mapOf("offset" to "500"), partition = "term10")
        cursors.save(sejm.id, IngestionMode.BACKFILL, mapOf("offset" to "0"), partition = "term9")

        scheduler.dispatchDueSources()

        assertEquals(
            setOf("ingestion:sejm:incremental", "ingestion:sejm:backfill:term10", "ingestion:sejm:backfill:term9"),
            queue.dedupKeys.toSet(),
        )
    }

    /** A dispatch running while the previous run is still queued must not queue it twice. */
    @Test
    fun `dispatching twice before anything runs queues one job`() {
        scheduler.dispatchDueSources()
        scheduler.dispatchDueSources()

        assertEquals(1, queue.queued.size)
    }

    private class OneSource(private val source: SourceDefinition) : SourceRegistry {
        override fun enabled() = listOf(source)

        override fun byConnector(connectorId: ConnectorId) = source.takeIf { it.connectorId == connectorId }

        override fun enabledById(id: SourceId) = source.takeIf { it.id == id }

        override fun byId(id: SourceId) = source.takeIf { it.id == id }
    }

    /** The run history reduced to the one question the cadence asks of it. */
    private class SpyingRuns : pl.barometr.sources.api.SourceRuns {
        var lastFinished: Instant? = null

        override fun start(sourceId: SourceId, mode: IngestionMode) = pl.barometr.sources.api.RunId(Ids.next())

        override fun finish(runId: pl.barometr.sources.api.RunId, outcome: RunOutcome, report: RunReport) = Unit

        override fun lastFinishedAt(sourceId: SourceId, mode: IngestionMode): Instant? =
            lastFinished.takeIf { mode == IngestionMode.INCREMENTAL }

        override fun recentAverageDocumentsSeen(sourceId: SourceId, mode: IngestionMode, runs: Int): Double? = null
    }
}
