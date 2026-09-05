package pl.barometr.alerts.internal

import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import pl.barometr.identity.api.WorkspaceInvitationIssued

/**
 * Turns "a seat has been offered to this address" into a message.
 *
 * Identity issues the invitation; sending mail is delivery, suppression and reputation,
 * which belong here. Without this listener an invitation is a row and a link nobody was
 * ever sent.
 */
@Component
class WorkspaceInvitationNotice(private val mails: InvitationMailQueue) {

    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun sendInvitation(invitation: WorkspaceInvitationIssued) {
        if (mails.queueInvitation(invitation)) {
            log.info("Inviting {} to {}", invitation.email, invitation.workspaceName)
        }
    }
}
