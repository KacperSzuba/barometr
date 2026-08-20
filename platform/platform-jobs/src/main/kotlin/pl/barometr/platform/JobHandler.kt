package pl.barometr.platform

/**
 * Handles one kind of job. Registered as a Spring bean; the worker finds it by
 * type, so adding a job kind means adding a bean and nothing else.
 *
 * Throwing signals failure: the worker translates that into a retry with backoff,
 * or a dead letter once attempts run out. A handler should therefore not swallow
 * exceptions to look tidy — that would turn a retryable failure into silent data
 * loss.
 */
interface JobHandler {
    val type: JobType

    fun handle(job: ClaimedJob)
}
