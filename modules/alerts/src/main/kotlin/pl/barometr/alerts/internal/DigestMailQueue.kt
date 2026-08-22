package pl.barometr.alerts.internal

import org.springframework.stereotype.Component
import pl.barometr.platform.ClaimedJob
import pl.barometr.platform.JobQueue
import pl.barometr.platform.JobType
import pl.barometr.platform.NewJob
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * The only way a digest reaches the mail queue.
 *
 * The job type, the dedup key and the payload format live together because they are one
 * fact: three places agreeing is what lets a job survive the trip, and the last time
 * they were apart a payload built by interpolation dead-lettered after five attempts.
 *
 * A queue rather than a scheduled sweep of unsent digests, and that is the point of
 * having one: the job is enqueued in the same transaction as the digest, so there is no
 * window where a window closed and nothing will ever send it — and retries, backoff
 * with jitter, and the dead letter after five attempts are already written.
 */
@Component
class DigestMailQueue(
    private val queue: JobQueue,
    private val json: ObjectMapper,
) {

    fun queueMail(digest: Digest): Boolean = queue.enqueue(
        NewJob(
            type = TYPE,
            payload = json.writeValueAsString(WirePayload(digest.id.toString())),
            // One mail per digest, whoever asks and however often.
            dedupKey = "$TYPE:${digest.id}",
        ),
    )

    fun digestOf(job: ClaimedJob): UUID =
        UUID.fromString(json.readValue(job.payload, WirePayload::class.java).digestId)

    data class WirePayload(val digestId: String = "")

    companion object {
        val TYPE = JobType("alerts.digest.mail")
    }
}
