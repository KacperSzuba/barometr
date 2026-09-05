package pl.barometr.alerts.internal

import org.springframework.stereotype.Component
import pl.barometr.identity.api.SignedInFromNewDevice
import pl.barometr.identity.api.UserId
import pl.barometr.platform.ClaimedJob
import pl.barometr.platform.JobPriority
import pl.barometr.platform.JobQueue
import pl.barometr.platform.JobType
import pl.barometr.platform.NewJob
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * The only way a new-device warning reaches the queue.
 *
 * Queued rather than sent where it is noticed, for the reason every other outbound
 * message here is: a mail server is somebody else's machine, and an SMTP conversation
 * inside a listener would make signing in wait for it. Type, dedup key and payload live
 * together because they are one fact.
 *
 * The key is the session, so a redelivered event — Spring Modulith republishes what a
 * listener never finished — sends one message rather than a second one.
 */
@Component
class NewDeviceMailQueue(
    private val queue: JobQueue,
    private val json: ObjectMapper,
) {

    fun queueWarning(signIn: SignedInFromNewDevice): Boolean = queue.enqueue(
        NewJob(
            type = TYPE,
            payload = json.writeValueAsString(
                WirePayload(
                    userId = signIn.userId.value.toString(),
                    sessionId = signIn.sessionId.toString(),
                    userAgent = signIn.userAgent,
                    clientIp = signIn.clientIp,
                    approximateLocation = signIn.approximateLocation,
                    occurredAt = signIn.occurredAt.toString(),
                ),
            ),
            // Somebody may be reading it while their password is being used by somebody
            // else: this is the one piece of mail here that is worth jumping a queue for.
            priority = JobPriority.INTERACTIVE,
            dedupKey = "alerts.new-device:${signIn.sessionId}",
        ),
    )

    fun warningIn(job: ClaimedJob): SignedInFromNewDevice {
        val wire = json.readValue(job.payload, WirePayload::class.java)

        return SignedInFromNewDevice(
            userId = UserId(UUID.fromString(wire.userId)),
            sessionId = UUID.fromString(wire.sessionId),
            userAgent = wire.userAgent,
            clientIp = wire.clientIp,
            approximateLocation = wire.approximateLocation,
            occurredAt = Instant.parse(wire.occurredAt),
        )
    }

    /** Wire forms, converted to domain types at this boundary and nowhere else. */
    internal data class WirePayload(
        val userId: String,
        val sessionId: String,
        val userAgent: String?,
        val clientIp: String?,
        val approximateLocation: String?,
        val occurredAt: String,
    )

    companion object {
        val TYPE = JobType("alerts.new-device-mail")
    }
}
