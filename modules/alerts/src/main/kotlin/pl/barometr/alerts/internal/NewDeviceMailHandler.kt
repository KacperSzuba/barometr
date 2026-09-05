package pl.barometr.alerts.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.barometr.identity.api.UserLookup
import pl.barometr.platform.ClaimedJob
import pl.barometr.platform.JobHandler
import pl.barometr.platform.JobType

/**
 * Sends one new-device warning.
 *
 * **An unsubscribe does not stop this one.** Somebody who asked to stop receiving
 * digests asked about digests; "stop telling me when my password is used somewhere new"
 * is not a preference this product offers, and honouring an unsubscribe here would be
 * offering it silently. A bounce or a spam complaint *does* stop it, because both mean
 * the message will not arrive and sending it again costs the domain its reputation.
 *
 * With no mail server configured there is no transport at all, and the job fails loudly
 * rather than quietly reporting success — the same rule the digest handler follows, and
 * for a message that matters more.
 */
@Component
class NewDeviceMailHandler(
    private val mails: NewDeviceMailQueue,
    private val suppressions: SuppressionRepository,
    private val users: UserLookup,
    private val compose: NewDeviceMail,
    private val meters: MeterRegistry,
    private val transport: EmailTransport?,
) : JobHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type: JobType get() = NewDeviceMailQueue.TYPE

    override fun handle(job: ClaimedJob) {
        val signIn = mails.warningIn(job)

        val address = users.findById(signIn.userId)?.email
            ?: return skip("no-account", signIn.userId.value.toString())

        val reason = suppressions.reasonFor(address)
        if (reason != null && reason != SuppressionReason.UNSUBSCRIBED) {
            return skip(reason.wireName, signIn.userId.value.toString())
        }

        val sender = transport ?: error("No mail transport configured; a security warning cannot be sent")
        sender.send(compose.compose(signIn, address))

        meters.counter("alerts.new_device.warned").increment()
        log.info("Warned {} about a sign-in on a new device", signIn.userId.value)
    }

    /**
     * A message that will not be sent, counted rather than retried: neither a missing
     * account nor a dead address becomes deliverable on the fourth attempt.
     */
    private fun skip(reason: String, user: String) {
        meters.counter("alerts.new_device.unsent", "reason", reason).increment()
        log.warn("No new-device warning to {}: {}", user, reason)
    }
}
