package pl.barometr.ingestion.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.barometr.platform.ClaimedJob
import pl.barometr.platform.JobHandler
import pl.barometr.platform.JobType
import pl.barometr.sources.api.SourceRegistry

/**
 * Runs one queued ingestion job.
 *
 * Everything it could get wrong about the payload is handled by [IngestionRunQueue],
 * which hands over a typed request; what is left here is the one decision that
 * belongs to execution time — whether the source is still one we are allowed to read.
 */
@Component
class IngestionJobHandler(
    private val runner: ConnectorRunner,
    private val sources: SourceRegistry,
    private val runQueue: IngestionRunQueue,
) : JobHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    override val type: JobType = IngestionRunQueue.TYPE

    override fun handle(job: ClaimedJob) {
        val request = runQueue.requestOf(job)
        val source = sources.enabledById(request.sourceId)

        if (source == null) {
            // Disabled or removed between enqueue and claim. Not a failure: the
            // dispatcher simply stops finding it due.
            log.info("Source {} is no longer enabled; dropping run", request.sourceId)
            return
        }

        // Any exception propagates: the queue applies backoff and retries, and the
        // run is already recorded as failed with its reason.
        runner.readSourceOnce(source, request.mode, request.partition)
    }
}
