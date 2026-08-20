package pl.barometr.platform

import java.time.Instant
import java.util.UUID

/** Discriminator a worker registers against, e.g. `ingest.sejm.incremental`. */
@JvmInline
value class JobType(val value: String) {
    init {
        require(value.isNotBlank()) { "Job type must not be blank" }
    }

    override fun toString(): String = value
}

data class NewJob(
    val type: JobType,
    /** JSON. Deliberately opaque here: the queue never interprets a payload. */
    val payload: String = "{}",
    /**
     * When the job becomes claimable. Null means as soon as a worker can take it —
     * expressed as an absence rather than as `Instant.now()`, so that "now" is
     * decided by the queue's clock rather than by whichever clock the producer
     * happened to read.
     */
    val runAfter: Instant? = null,
    /**
     * Lower runs first. Backfill sits above [BACKGROUND] so a five-year replay
     * never delays today's documents.
     */
    val priority: Int = DEFAULT_PRIORITY,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /**
     * When set, enqueueing is idempotent: a second job with the same key is
     * dropped while the first is still pending or running.
     */
    val dedupKey: String? = null,
) {
    companion object {
        const val INTERACTIVE = 10
        const val DEFAULT_PRIORITY = 100
        const val BACKGROUND = 500
        const val DEFAULT_MAX_ATTEMPTS = 5
    }
}

data class ClaimedJob(
    val id: UUID,
    val type: JobType,
    val payload: String,
    /** 1 on the first run. Lets a handler behave differently on a retry. */
    val attempt: Int,
    val maxAttempts: Int,
) {
    val isFinalAttempt: Boolean get() = attempt >= maxAttempts
}

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
