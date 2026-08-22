package pl.barometr.alerts.internal

import jakarta.mail.internet.MimeMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import java.nio.charset.StandardCharsets

/**
 * SMTP, through Spring's own sender.
 *
 * SMTP rather than a provider's REST API because both providers this is aimed at speak
 * it, so the choice between them stays a matter of `spring.mail.*` — and because the
 * alternative was a hand-written HTTP client plus a recorded fixture nobody can record
 * without an account.
 *
 * The `List-Unsubscribe` headers are set here rather than left to the provider: both of
 * them will add their own if asked, and both then need configuring separately. One
 * place, and it travels with whichever provider is in use.
 */
class SmtpEmailTransport(
    private val mailer: JavaMailSender,
    private val from: String,
) : EmailTransport {

    override fun send(message: EmailMessage) {
        mailer.send(compose(message))
    }

    private fun compose(message: EmailMessage): MimeMessage {
        val mime = mailer.createMimeMessage()
        // `true` for multipart: the text and HTML parts are alternatives of one message,
        // not two messages or an attachment.
        val helper = MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name())

        helper.setFrom(from)
        helper.setTo(message.to)
        helper.setSubject(message.subject)
        helper.setText(message.text, message.html)

        mime.setHeader(LIST_UNSUBSCRIBE, "<${message.unsubscribeUrl}>")
        // What lets a mail client offer one-click unsubscribe rather than opening the
        // link and hoping. Gmail and Outlook both require it before they will show the
        // button at all.
        mime.setHeader(ONE_CLICK, "List-Unsubscribe=One-Click")

        return mime
    }

    private companion object {
        const val LIST_UNSUBSCRIBE = "List-Unsubscribe"
        const val ONE_CLICK = "List-Unsubscribe-Post"
    }
}
