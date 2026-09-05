package pl.barometr.alerts.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.barometr.platform.ClaimedJob
import pl.barometr.platform.JobHandler
import pl.barometr.platform.JobType

/**
 * Sends one invitation.
 *
 * **A suppressed address stops it, whatever the reason.** Unlike the new-device warning,
 * this is not a message somebody is owed: an address that bounced will not receive it,
 * and one whose owner pressed "spam" is one this domain must not write to again. The
 * person inviting sees the invitation sitting open and can ask their colleague for
 * another address, which is the honest outcome.
 */
@Component
class InvitationMailHandler(
    private val mails: InvitationMailQueue,
    private val suppressions: SuppressionRepository,
    private val compose: InvitationMail,
    private val meters: MeterRegistry,
    private val transport: EmailTransport?,
) : JobHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type: JobType get() = InvitationMailQueue.TYPE

    override fun handle(job: ClaimedJob) {
        val invitation = mails.invitationIn(job)

        val reason = suppressions.reasonFor(invitation.email)
        if (reason != null) {
            meters.counter("alerts.invitation.unsent", "reason", reason.wireName).increment()
            return log.warn("No invitation sent to {}: {}", invitation.email, reason.wireName)
        }

        val sender = transport ?: error("No mail transport configured; an invitation cannot be sent")
        sender.send(compose.compose(invitation))

        meters.counter("alerts.invitation.sent").increment()
        log.info("Invitation to {} sent", invitation.workspaceName)
    }
}
