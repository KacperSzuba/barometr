package pl.barometr.alerts.internal

import pl.barometr.platform.ClaimedJob
import pl.barometr.platform.JobQueue
import pl.barometr.platform.NewJob
import java.time.Instant
import java.util.UUID

/**
 * The queue, remembering what it was handed.
 *
 * What the tests around it care about is that closing a window queues exactly one mail
 * for it — the retries, the backoff and the dead letter are the platform's, and are
 * tested where they live.
 */
class FakeJobQueue : JobQueue {
    private val queued = mutableListOf<NewJob>()

    val jobs: List<NewJob> get() = queued

    override fun enqueue(job: NewJob): Boolean {
        // The real queue drops a job whose dedup key is already in flight, and the
        // tests here rely on that being true.
        if (queued.any { it.dedupKey != null && it.dedupKey == job.dedupKey }) return false

        queued.add(job)
        return true
    }

    override fun claim(worker: String, limit: Int): List<ClaimedJob> =
        queued.take(limit).map { ClaimedJob(UUID.randomUUID(), it.type, it.payload, 1, it.maxAttempts) }

    override fun succeed(jobId: UUID) = Unit

    override fun fail(jobId: UUID, error: String) = Unit

    override fun reclaimAbandoned(olderThan: Instant): Int = 0
}
