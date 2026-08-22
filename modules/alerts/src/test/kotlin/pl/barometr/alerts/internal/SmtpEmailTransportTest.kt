package pl.barometr.alerts.internal

import org.junit.jupiter.api.Test
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.web.client.RestClient
import pl.barometr.testing.MailTestServer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a mail server actually receives.
 *
 * Against a real one, because everything this class exists to get right is invisible on
 * the sending side: whether the two bodies arrived as alternatives of one message, and
 * whether the headers that let a mail client offer one-click unsubscribe are there at
 * all. A fake `JavaMailSender` would have let every one of them be wrong.
 */
class SmtpEmailTransportTest {

    private val mailer = JavaMailSenderImpl().apply {
        host = MailTestServer.host
        port = MailTestServer.smtpPort
    }

    private val transport = SmtpEmailTransport(mailer, "alerty@barometr.example")
    private val inbox = RestClient.create(MailTestServer.apiBaseUrl)

    @Test
    fun `a digest arrives with both bodies and a way out`() {
        val address = "ewa+${System.nanoTime()}@example.com"

        transport.send(
            EmailMessage(
                to = address,
                subject = "Barometr: 1 sprawa",
                text = "Prawo budowlane",
                html = "<h1>Prawo budowlane</h1>",
                unsubscribeUrl = "https://barometr.example/api/v1/alerts/unsubscribe/abc",
            ),
        )

        val received = messageFor(address)

        assertEquals("Barometr: 1 sprawa", received.subject)
        assertEquals("alerty@barometr.example", received.from)
        assertTrue(received.text.contains("Prawo budowlane"), "the text part is missing")
        assertTrue(received.html.contains("<h1>"), "the html part is missing")

        // The two headers together are what makes a mail client show an unsubscribe
        // button instead of leaving the reader to find the "spam" one.
        assertEquals(
            "<https://barometr.example/api/v1/alerts/unsubscribe/abc>",
            received.header("List-Unsubscribe"),
        )
        assertEquals("List-Unsubscribe=One-Click", received.header("List-Unsubscribe-Post"))
    }

    /** A subject in Polish survives the trip, which is a question about encoding. */
    @Test
    fun `Polish characters arrive as they were written`() {
        val address = "marek+${System.nanoTime()}@example.com"

        transport.send(
            EmailMessage(
                to = address,
                subject = "Barometr: 3 sprawy — zmiany w prawie",
                text = "Ustawa o zmianie ustawy — Prawo budowlane",
                html = "<p>Ustawa o zmianie ustawy — Prawo budowlane</p>",
                unsubscribeUrl = "https://barometr.example/u/abc",
            ),
        )

        val received = messageFor(address)

        assertEquals("Barometr: 3 sprawy — zmiany w prawie", received.subject)
        assertTrue(received.text.contains("— Prawo budowlane"))
    }

    private fun messageFor(address: String): ReceivedMessage {
        val summary = inbox.get()
            .uri("/api/v1/search?query={query}", "to:$address")
            .retrieve()
            .body(Map::class.java)
            .orEmpty()

        val id = (summary["messages"] as List<*>).map { it as Map<*, *> }.single()["ID"].toString()
        val full = inbox.get().uri("/api/v1/message/{id}", id).retrieve().body(Map::class.java).orEmpty()
        val headers = inbox.get()
            .uri("/api/v1/message/{id}/headers", id)
            .retrieve()
            .body(Map::class.java)
            .orEmpty()

        return ReceivedMessage(
            subject = full["Subject"].toString(),
            from = ((full["From"] as Map<*, *>)["Address"]).toString(),
            text = full["Text"].toString(),
            html = full["HTML"].toString(),
            headers = headers,
        )
    }

    private class ReceivedMessage(
        val subject: String,
        val from: String,
        val text: String,
        val html: String,
        private val headers: Map<*, *>,
    ) {
        fun header(name: String): String? = (headers[name] as? List<*>)?.firstOrNull()?.toString()
    }
}
