package pl.barometr.alerts.internal

import org.springframework.stereotype.Component
import pl.barometr.identity.api.WorkspaceInvitationIssued
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The message that carries a seat.
 *
 * It says who invited them and to what, because a message that says only "you have been
 * invited" is one people report as spam — and because the reader is usually somebody who
 * has never heard of this product and is deciding, in about two seconds, whether it is a
 * phishing attempt.
 *
 * No unsubscribe link: this is one message to one address that somebody typed in on
 * purpose, not a subscription. See [EmailMessage].
 */
@Component
class InvitationMail {

    fun compose(invitation: WorkspaceInvitationIssued): EmailMessage {
        val until = FORMAT.format(invitation.expiresAt.atZone(WARSAW))
        val by = invitation.invitedBy.takeIf { it.isNotBlank() }?.let { " przez $it" }.orEmpty()

        return EmailMessage(
            to = invitation.email,
            subject = "Zaproszenie do zespołu ${invitation.workspaceName} w Barometrze",
            text = """
                Zostałeś zaproszony$by do zespołu „${invitation.workspaceName}" w Barometrze.

                Zaproszenie przyjmiesz tutaj: ${invitation.acceptUrl}
                Link działa do $until.

                Jeśli nie wiesz, czego dotyczy ta wiadomość — zignoruj ją. Bez kliknięcia nic się nie stanie.
            """.trimIndent(),
            html = """
                <p>Zostałeś zaproszony${escape(by)} do zespołu <strong>${escape(invitation.workspaceName)}</strong>
                w Barometrze.</p>
                <p><a href="${escape(invitation.acceptUrl)}">Przyjmij zaproszenie</a><br>
                Link działa do $until.</p>
                <p>Jeśli nie wiesz, czego dotyczy ta wiadomość — zignoruj ją. Bez kliknięcia nic się nie stanie.</p>
            """.trimIndent(),
        )
    }

    /** Everything here came from somebody else: a workspace name, an address, a URL. */
    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private companion object {
        val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

        /** Polish explicitly, never the JVM's default locale. */
        val FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("pl"))
    }
}
