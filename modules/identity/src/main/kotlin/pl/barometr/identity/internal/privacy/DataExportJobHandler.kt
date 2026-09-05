package pl.barometr.identity.internal.privacy

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.barometr.platform.ClaimedJob
import pl.barometr.platform.JobHandler
import pl.barometr.platform.JobType
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Assembles one export.
 *
 * A failure on the last attempt is written down as a failure rather than left as a
 * request nobody answered: a statutory deadline is missed inside exactly that state, and
 * "requested three weeks ago" is indistinguishable from a queue that has stopped.
 */
@Component
class DataExportJobHandler(
    private val exports: AccountDataExports,
    private val json: ObjectMapper,
) : JobHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type: JobType get() = AccountDataExports.TYPE

    override fun handle(job: ClaimedJob) {
        val payload = json.readValue(job.payload, AccountDataExports.WirePayload::class.java)
        val id = UUID.fromString(payload.exportId)

        try {
            exports.assembleExport(id, UUID.fromString(payload.userId))
        } catch (failure: Exception) {
            if (job.isFinalAttempt) {
                log.error("Data export {} could not be assembled", id, failure)
                exports.recordFailure(id, "${failure.javaClass.simpleName}: ${failure.message}")
                return
            }
            // Not the last attempt: let the queue back off and try again.
            throw failure
        }
    }
}
