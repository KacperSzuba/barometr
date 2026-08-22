package pl.barometr.platform.internal

import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.barometr.platform.ClaimedJob
import pl.barometr.platform.JobHandler
import pl.barometr.platform.JobQueue
import pl.barometr.platform.JobType
import java.net.InetAddress
import java.time.Clock

/**
 * Polls the queue and dispatches to handlers.
 *
 * Deliberately a `@Scheduled` method rather than a bespoke thread pool: Spring
 * already owns the scheduler, and with virtual threads enabled each poll runs on
 * one, so a blocking handler costs no platform thread. Nothing here needs to
 * exist except the dispatch itself.
 *
 * Note the absence of `@SchedulerLock` on the poll: every instance *should* poll.
 * `SKIP LOCKED` is what keeps them from colliding, and serialising workers would
 * throw away the only reason the queue scales.
 */
@Component
class JobWorker(
    private val queue: JobQueue,
    handlers: List<JobHandler>,
    private val properties: JobWorkerProperties,
    private val tracing: JobTracing,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val handlersByType: Map<JobType, JobHandler> = handlers.associateBy { it.type }
    private val workerId = "${InetAddress.getLocalHost().hostName}/${ProcessHandle.current().pid()}"

    init {
        log.info("Job worker {} ready for types {}", workerId, handlersByType.keys.map { it.value })
    }

    @Scheduled(fixedDelayString = "\${app.jobs.poll-interval:1000}")
    fun claimAndRun() {
        queue.claim(workerId, properties.batchSize).forEach(::runHandlerFor)
    }

    private fun runHandlerFor(job: ClaimedJob) {
        val handler = handlersByType[job.type]
        if (handler == null) {
            // A payload nobody can process is a deployment mistake, not a transient
            // failure — fail it so it reaches the dead letter instead of spinning.
            log.error("No handler registered for job type {}", job.type)
            queue.fail(job.id, "no handler registered for ${job.type}")
            return
        }

        val timer = meters.timer("jobs.execution", "type", job.type.value)
        try {
            // Continuing whoever queued it rather than starting afresh. Without this a
            // document's journey is three unrelated traces: the request that asked for
            // it, the fetch, and the alert it became.
            timer.recordCallable { tracing.continuing(job.traceContext, job.type.value) { handler.handle(job) } }
            queue.succeed(job.id)
        } catch (failure: Exception) {
            meters.counter("jobs.failures", "type", job.type.value).increment()
            log.warn("Job {} of type {} failed on attempt {}", job.id, job.type, job.attempt, failure)
            queue.fail(job.id, failure.message ?: failure::class.qualifiedName ?: "unknown failure")
        }
    }

    /**
     * Returns work abandoned by a crashed worker. Locked here, because the sweep
     * is idempotent but pointless to run on every instance at once.
     */
    @Scheduled(fixedDelayString = "\${app.jobs.reaper-interval:60000}")
    @SchedulerLock(name = "job-reaper")
    fun reclaimAbandoned() {
        val reclaimed = queue.reclaimAbandoned(clock.instant().minus(properties.abandonedAfter))
        if (reclaimed > 0) log.warn("Reclaimed {} abandoned job(s)", reclaimed)
    }
}
