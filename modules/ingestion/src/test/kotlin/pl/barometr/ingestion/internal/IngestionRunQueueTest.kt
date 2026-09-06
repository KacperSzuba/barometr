package pl.barometr.ingestion.internal

import org.junit.jupiter.api.Test
import pl.barometr.platform.JobPriority
import pl.barometr.shared.Ids
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.IngestionMode
import pl.barometr.sources.api.SourceDefinition
import pl.barometr.sources.api.SourceId
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The trip a run makes through the queue and back.
 *
 * Three things have to agree for it to survive: the job type a handler registers
 * against, the dedup key that decides whether the work is already in flight, and the
 * payload format. They were once a companion function two classes reached into and a
 * payload built by string interpolation — so a partition key containing a quote
 * produced JSON the handler could not read, and the job dead-lettered after five
 * attempts. This is the round trip that would have caught it.
 */
class IngestionRunQueueTest {

    private val queue = RecordingJobQueue()
    private val runQueue = IngestionRunQueue(queue, JsonMapper.builder().addModule(kotlinModule()).build())

    private val sejm = SourceDefinition(
        id = SourceId(Ids.next()),
        connectorId = ConnectorId("sejm"),
        name = "API Sejmu",
        baseUrl = URI.create("https://api.sejm.gov.pl"),
        refreshInterval = Duration.ofMinutes(15),
        expectedMinRecordsPerRun = null,
    )

    private val now: Instant = Instant.parse("2026-08-21T10:00:00Z")

    @Test
    fun `what is queued is what the handler reads back`() {
        runQueue.queueRun(sejm, IngestionMode.BACKFILL, now, partition = "term10")

        val request = runQueue.requestOf(queue.claim("worker", 1).single())

        assertEquals(sejm.id, request.sourceId)
        assertEquals(IngestionMode.BACKFILL, request.mode)
        assertEquals("term10", request.partition)
    }

    /**
     * The defect this class was written to end. A partition is a key from a source, and
     * a source may one day name one with a quote in it.
     */
    @Test
    fun `a partition whose name would break hand-written JSON survives the trip`() {
        val awkward = """term"10\ i 9"""

        runQueue.queueRun(sejm, IngestionMode.BACKFILL, now, partition = awkward)

        assertEquals(awkward, runQueue.requestOf(queue.claim("worker", 1).single()).partition)
    }

    @Test
    fun `a run already in flight is not queued a second time`() {
        assertTrue(runQueue.queueRun(sejm, IngestionMode.INCREMENTAL, now))

        assertFalse(runQueue.queueRun(sejm, IngestionMode.INCREMENTAL, now))
        assertEquals(1, queue.queued.size)
    }

    /** Every partition of a backfill can be in flight at once; one key for all would serialise them. */
    @Test
    fun `two partitions of one backfill are two pieces of work`() {
        runQueue.queueRun(sejm, IngestionMode.BACKFILL, now, partition = "term10")
        runQueue.queueRun(sejm, IngestionMode.BACKFILL, now, partition = "term9")

        assertEquals(
            listOf("ingestion:sejm:backfill:term10", "ingestion:sejm:backfill:term9"),
            queue.dedupKeys,
        )
    }

    /** And the two modes are two pieces of work, so a replay never blocks today's poll. */
    @Test
    fun `a backfill and an incremental run of one source do not exclude each other`() {
        runQueue.queueRun(sejm, IngestionMode.INCREMENTAL, now)
        runQueue.queueRun(sejm, IngestionMode.BACKFILL, now, partition = "term10")

        assertEquals(2, queue.queued.size)
    }

    /** A five-year replay must never delay the quarter-hourly poll of what is happening now. */
    @Test
    fun `a backfill is queued below live ingestion`() {
        runQueue.queueRun(sejm, IngestionMode.INCREMENTAL, now)
        runQueue.queueRun(sejm, IngestionMode.BACKFILL, now, partition = "term10")

        assertEquals(JobPriority.STANDARD, queue.queued.first().priority)
        assertEquals(JobPriority.BACKGROUND, queue.queued.last().priority)
    }

    @Test
    fun `a queued run carries the type the handler registered against`() {
        runQueue.queueRun(sejm, IngestionMode.INCREMENTAL, now)

        assertEquals(IngestionRunQueue.TYPE, queue.queued.single().type)
    }
}
