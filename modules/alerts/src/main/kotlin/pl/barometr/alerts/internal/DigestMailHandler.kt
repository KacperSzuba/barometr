package pl.barometr.alerts.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.UserLookup
import pl.barometr.platform.ClaimedJob
import pl.barometr.platform.JobHandler
import pl.barometr.platform.JobType
import java.util.UUID

/**
 * Sends one digest, once.
 *
 * Everything that can stop it is asked before the transport, and each answer is written
 * down: an address on the suppression list, a digest that already went out, an account
 * that no longer exists. What is left is a message, and if the transport refuses it the
 * exception goes back to the queue — which is what backoff and the dead letter are for.
 *
 * With no mail server configured there is no transport at all, and the job fails
 * loudly rather than quietly reporting success. A developer machine has no
 * `spring.mail.host`, and a digest that silently counted as delivered would be a lie
 * told once per window.
 */
@Component
class DigestMailHandler(
    private val mails: DigestMailQueue,
    private val digests: DigestRepository,
    private val notifications: NotificationRepository,
    private val deliveries: EmailDeliveryRepository,
    private val suppressions: SuppressionRepository,
    private val tokens: UnsubscribeTokenRepository,
    private val users: UserLookup,
    private val compose: DigestMail,
    private val properties: EmailProperties,
    /**
     * Absent when no mail server is configured. A nullable constructor parameter is how
     * Spring is told a dependency is optional in Kotlin, and it keeps this class
     * constructible in a test without a container's worth of ceremony.
     */
    private val transport: EmailTransport?,
) : JobHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type: JobType get() = DigestMailQueue.TYPE

    override fun handle(job: ClaimedJob) {
        val digestId = mails.digestOf(job)

        // A retry after the transport accepted the message but before the row was
        // written would otherwise send it twice, and a duplicate digest reads as a bug
        // to the person receiving it.
        if (deliveries.wasSent(digestId)) return

        val digest = digests.byId(digestId) ?: return log.warn("Digest {} is gone", digestId)
        val owner = digests.ownerOf(digestId) ?: return log.warn("Digest {} has no owner", digestId)

        send(digest, owner)
    }

    private fun send(digest: Digest, owner: UserId) {
        // A token can outlive the account it names: the digest stays, the user does not.
        val address = users.findById(owner)?.email
            ?: return deliveries.record(digest.id, owner, "", DeliveryStatus.FAILED, "no such account")

        if (suppressions.suppresses(address)) {
            // Not an error and not a retry. This address is never written to again, and
            // saying so is what stops the queue trying five times to find that out.
            return deliveries.record(
                digest.id,
                owner,
                address,
                DeliveryStatus.SUPPRESSED,
                suppressions.reasonFor(address)?.wireName,
            )
        }

        val contents = DigestContents.of(digest, notifications.inDigest(digest.id))
        val message = compose.compose(contents, address, unsubscribeUrlFor(owner))

        try {
            post().send(message)
        } catch (failure: Exception) {
            // Recorded and rethrown: the row says what happened, and the queue decides
            // whether to try again. Swallowing it would turn a full mailbox into
            // silence.
            deliveries.record(digest.id, owner, address, DeliveryStatus.FAILED, failure.message)
            throw failure
        }

        deliveries.record(digest.id, owner, address, DeliveryStatus.SENT)
    }

    private fun post(): EmailTransport =
        transport ?: error("no mail transport: set spring.mail.host to send digests")

    private fun unsubscribeUrlFor(owner: UserId): String =
        "${properties.unsubscribeBaseUrl}/api/v1/alerts/unsubscribe/${tokens.tokenFor(owner)}"
}
