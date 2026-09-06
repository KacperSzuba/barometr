package pl.barometr.ingestion.internal

import pl.barometr.platform.ClaimedJob
import pl.barometr.platform.JobQueue
import pl.barometr.platform.NewJob
import java.time.Instant
import java.util.UUID

/**
 * The queue, in a list, keeping only the promise its callers here depend on: a second
 * job with a dedup key already in flight is dropped.
 *
 * What the real queue does under concurrency — `FOR UPDATE SKIP LOCKED`, the partial
 * unique index that makes the dedup a decision of the database rather than of a
 * read-then-write race — is proved against a real Postgres in `JooqJobQueueTest`.
 * Repeating it here would be testing that test.
 */
class RecordingJobQueue : JobQueue {

    val queued = mutableListOf<NewJob>()

    override fun enqueue(job: NewJob): Boolean {
        val alreadyInFlight = job.dedupKey != null && queued.any { it.dedupKey == job.dedupKey }
        if (alreadyInFlight) return false

        queued += job
        return true
    }

    override fun claim(worker: String, limit: Int): List<ClaimedJob> =
        queued.take(limit).map { job ->
            ClaimedJob(
                id = UUID.randomUUID(),
                type = job.type,
                payload = job.payload,
                attempt = 1,
                maxAttempts = job.maxAttempts,
            )
        }

    override fun succeed(jobId: UUID) = Unit

    override fun fail(jobId: UUID, error: String) = Unit

    override fun reclaimAbandoned(olderThan: Instant): Int = 0

    val dedupKeys: List<String?> get() = queued.map { it.dedupKey }
}
