package pl.barometr.alerts.internal

import org.springframework.stereotype.Component
import pl.barometr.identity.api.WorkspaceInvitationIssued
import pl.barometr.platform.ClaimedJob
import pl.barometr.platform.JobPriority
import pl.barometr.platform.JobQueue
import pl.barometr.platform.JobType
import pl.barometr.platform.NewJob
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * The only way an invitation message reaches the queue.
 *
 * The link is in the payload, which is worth saying out loud: the queue's rows hold a
 * capability for as long as the job is pending. That is the same trade the digest makes
 * with its unsubscribe link, and it is bounded by the invitation's own expiry.
 *
 * Deduplicated on the link, so a redelivered event sends one message rather than two.
 */
@Component
class InvitationMailQueue(
    private val queue: JobQueue,
    private val json: ObjectMapper,
) {

    fun queueInvitation(invitation: WorkspaceInvitationIssued): Boolean = queue.enqueue(
        NewJob(
            type = TYPE,
            payload = json.writeValueAsString(
                WirePayload(
                    email = invitation.email,
                    workspaceName = invitation.workspaceName,
                    invitedBy = invitation.invitedBy,
                    acceptUrl = invitation.acceptUrl,
                    expiresAt = invitation.expiresAt.toString(),
                    occurredAt = invitation.occurredAt.toString(),
                ),
            ),
            // Somebody is waiting on the other side of this: an invitation that arrives
            // after the meeting it was sent during is an invitation nobody accepts.
            priority = JobPriority.INTERACTIVE,
            dedupKey = "alerts.invitation:${invitation.acceptUrl}",
        ),
    )

    fun invitationIn(job: ClaimedJob): WorkspaceInvitationIssued {
        val wire = json.readValue(job.payload, WirePayload::class.java)

        return WorkspaceInvitationIssued(
            email = wire.email,
            workspaceName = wire.workspaceName,
            invitedBy = wire.invitedBy,
            acceptUrl = wire.acceptUrl,
            expiresAt = Instant.parse(wire.expiresAt),
            occurredAt = Instant.parse(wire.occurredAt),
        )
    }

    /** Wire forms, converted to domain types at this boundary and nowhere else. */
    internal data class WirePayload(
        val email: String,
        val workspaceName: String,
        val invitedBy: String,
        val acceptUrl: String,
        val expiresAt: String,
        val occurredAt: String,
    )

    companion object {
        val TYPE = JobType("alerts.invitation-mail")
    }
}
