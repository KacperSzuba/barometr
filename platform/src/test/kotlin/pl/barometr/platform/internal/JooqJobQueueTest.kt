package pl.barometr.platform.internal

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.platform.JobPriority
import pl.barometr.platform.JobType
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import pl.barometr.platform.NewJob
import pl.barometr.platform.internal.jooq.tables.references.JOB
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The queue's contract is a concurrency contract, so it is tested against a real
 * Postgres. H2 would accept the SQL and quietly ignore `SKIP LOCKED`, which is
 * the one thing worth proving here.
 */
class JooqJobQueueTest {

    private val dsl: DSLContext = PostgresTestDatabase.dsl()
    private val clock = TestClock()
    private lateinit var queue: JooqJobQueue

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(JOB).execute()
        queue = JooqJobQueue(dsl, JobBackoffPolicy(), clock)
    }

    @Test
    fun `claims an enqueued job exactly once`() {
        assertTrue(queue.enqueue(NewJob(type = INGEST, payload = """{"page":1}""")))

        val claimed = queue.claim(worker = "w1", limit = 10)
        assertEquals(1, claimed.size)
        assertEquals(INGEST, claimed.single().type)
        assertEquals("""{"page": 1}""", claimed.single().payload)
        assertEquals(1, claimed.single().attempt)

        // Already running, so a second poll finds nothing.
        assertTrue(queue.claim(worker = "w2", limit = 10).isEmpty())
    }

    @Test
    fun `a duplicate key is rejected while the original is live`() {
        assertTrue(queue.enqueue(NewJob(type = INGEST, dedupKey = "sejm:page:1")))
        assertFalse(queue.enqueue(NewJob(type = INGEST, dedupKey = "sejm:page:1")))

        val claimed = queue.claim(worker = "w1", limit = 10)
        assertEquals(1, claimed.size)

        // Still running — the key stays taken.
        assertFalse(queue.enqueue(NewJob(type = INGEST, dedupKey = "sejm:page:1")))

        // Finished, so the same work may be scheduled again.
        queue.succeed(claimed.single().id)
        assertTrue(queue.enqueue(NewJob(type = INGEST, dedupKey = "sejm:page:1")))
    }

    @Test
    fun `jobs scheduled for later are not claimed yet`() {
        queue.enqueue(NewJob(type = INGEST, runAfter = clock.instant().plus(1, ChronoUnit.HOURS)))
        assertTrue(queue.claim(worker = "w1", limit = 10).isEmpty())
    }

    @Test
    fun `a more urgent job is claimed first`() {
        queue.enqueue(NewJob(type = BACKFILL, priority = JobPriority.BACKGROUND, dedupKey = "b"))
        queue.enqueue(NewJob(type = INGEST, priority = JobPriority.INTERACTIVE, dedupKey = "i"))

        assertEquals(INGEST, queue.claim(worker = "w1", limit = 1).single().type)
    }

    @Test
    fun `failure reschedules with backoff, then dead-letters`() {
        queue.enqueue(NewJob(type = INGEST, maxAttempts = 2))

        val first = queue.claim(worker = "w1", limit = 1).single()
        queue.fail(first.id, "source timed out")

        // Back to pending, but pushed into the future — so an immediate poll misses it.
        assertTrue(queue.claim(worker = "w1", limit = 1).isEmpty())
        assertEquals("pending", statusOf(first.id))

        // Let the backoff elapse. Moving the clock rather than rewriting `run_after`
        // means the policy's own delay is what is being tested.
        clock.advanceBy(java.time.Duration.ofHours(2))

        val second = queue.claim(worker = "w1", limit = 1).single()
        assertEquals(2, second.attempt)
        assertTrue(second.isFinalAttempt)

        queue.fail(second.id, "source timed out again")
        assertEquals("dead", statusOf(second.id))
        assertTrue(queue.claim(worker = "w1", limit = 1).isEmpty())
    }

    @Test
    fun `a job abandoned by a dead worker is reclaimed`() {
        queue.enqueue(NewJob(type = INGEST))
        queue.claim(worker = "doomed", limit = 1).single()

        // The worker died holding the lock two hours ago.
        clock.advanceBy(java.time.Duration.ofHours(2))

        assertEquals(0, queue.reclaimAbandoned(clock.instant().minus(4, ChronoUnit.HOURS)))
        assertEquals(1, queue.reclaimAbandoned(clock.instant().minus(1, ChronoUnit.HOURS)))
        assertEquals(1, queue.claim(worker = "healthy", limit = 1).size)
    }

    /**
     * The reason this queue is safe to run on more than one node: two workers
     * polling simultaneously must never receive the same job. Without SKIP
     * LOCKED one of them blocks and then claims rows the other already took.
     */
    @Test
    fun `concurrent workers never receive the same job`() {
        val jobCount = 40
        repeat(jobCount) { queue.enqueue(NewJob(type = INGEST, dedupKey = "job-$it")) }

        val workers = 4
        val barrier = CyclicBarrier(workers)
        val pool = Executors.newFixedThreadPool(workers)

        val tasks = (1..workers).map { worker ->
            Callable {
                // Every worker starts polling at the same instant.
                barrier.await(10, TimeUnit.SECONDS)
                JooqJobQueue(PostgresTestDatabase.dsl(), JobBackoffPolicy(), clock)
                    .claim(worker = "w$worker", limit = jobCount)
                    .map { it.id }
            }
        }

        val claimed = pool.invokeAll(tasks).flatMap { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(jobCount, claimed.size, "every job should be claimed exactly once")
        assertEquals(jobCount, claimed.toSet().size, "no job may be claimed twice")
    }

    private fun statusOf(id: java.util.UUID): String? =
        dsl.select(JOB.STATUS).from(JOB).where(JOB.ID.eq(id)).fetchOne()?.value1()

    companion object {
        private val INGEST = JobType("ingest.sejm.incremental")
        private val BACKFILL = JobType("ingest.sejm.backfill")
    }
}
