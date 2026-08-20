package pl.barometr.platform

import java.time.Instant
import java.util.UUID

/**
 * Durable work queue backed by Postgres.
 *
 * A broker was not used on purpose. Enqueueing in the same transaction as the
 * data that caused it removes an entire class of bug — a job that fires for a
 * row that was rolled back, or a row committed with no job to process it — and
 * at this system's volumes `FOR UPDATE SKIP LOCKED` delivers the same
 * at-least-once semantics with nothing extra to operate.
 */
interface JobQueue {

    /** Returns false when [NewJob.dedupKey] matches a job already pending or running. */
    fun enqueue(job: NewJob): Boolean

    /**
     * Takes up to [limit] jobs for [worker]. Concurrent callers never receive the
     * same job: rows already locked are skipped rather than waited on.
     */
    fun claim(worker: String, limit: Int): List<ClaimedJob>

    fun succeed(jobId: UUID)

    /**
     * Reschedules with exponential backoff, or dead-letters once attempts are
     * exhausted. A dead job is kept, never deleted — losing the record of what
     * failed is how a queue becomes unexplainable.
     */
    fun fail(jobId: UUID, error: String)

    /**
     * Returns jobs whose worker died holding the lock. Without this a crash
     * leaves work claimed forever, and the queue silently stops making progress.
     */
    fun reclaimAbandoned(olderThan: Instant): Int
}
