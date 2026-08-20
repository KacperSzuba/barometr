package pl.barometr.platform

import java.time.Instant

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
    val priority: JobPriority = JobPriority.STANDARD,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /**
     * When set, enqueueing is idempotent: a second job with the same key is
     * dropped while the first is still pending or running.
     */
    val dedupKey: String? = null,
) {
    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5
    }
}
