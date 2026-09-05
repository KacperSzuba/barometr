package pl.barometr.alerts.internal

import org.springframework.stereotype.Component
import pl.barometr.identity.api.SignedInFromNewDevice
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The message somebody gets when their account is used on a device it has not been used
 * on before.
 *
 * **It says what to do, in one sentence, at the top.** A security notice that opens with
 * an explanation of what a session is has already lost the reader it was written for. If
 * this was them, there is nothing to do; if it was not, the password has to change and
 * the device list is where the session is ended.
 *
 * **It carries no unsubscribe link**, which is the one message in this system that does
 * not — see [EmailMessage].
 */
@Component
class NewDeviceMail {

    fun compose(signIn: SignedInFromNewDevice, address: String): EmailMessage {
        val at = FORMAT.format(signIn.occurredAt.atZone(WARSAW))
        val device = signIn.userAgent ?: "nieznane urządzenie"
        val place = signIn.approximateLocation?.let { ", $it" }.orEmpty()
        val from = signIn.clientIp?.let { " z adresu $it$place" }.orEmpty()

        return EmailMessage(
            to = address,
            subject = "Nowe logowanie do konta Barometr",
            text = text(at, device, from),
            html = html(at, device, from),
        )
    }

    private fun text(at: String, device: String, from: String): String = """
        Ktoś zalogował się do Twojego konta na urządzeniu, którego wcześniej nie używaliśmy do logowania.

        Jeśli to byłeś Ty — nic nie musisz robić.
        Jeśli nie — zmień hasło i zakończ tę sesję na liście urządzeń w ustawieniach konta.

        Kiedy: $at
        Urządzenie: $device$from
    """.trimIndent()

    private fun html(at: String, device: String, from: String): String = """
        <p>Ktoś zalogował się do Twojego konta na urządzeniu, którego wcześniej nie używaliśmy do logowania.</p>
        <p><strong>Jeśli to byłeś Ty — nic nie musisz robić.</strong><br>
        Jeśli nie — zmień hasło i zakończ tę sesję na liście urządzeń w ustawieniach konta.</p>
        <p>Kiedy: $at<br>Urządzenie: ${escape(device)}$from</p>
    """.trimIndent()

    /** A user agent is a string somebody else chose; it is not put into HTML as it arrived. */
    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        /** The reader's own clock, not the server's: "14:20" means nothing in UTC to somebody in Warsaw. */
        val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

        /** Polish explicitly, never the JVM's default locale — a month name is not a place to guess. */
        val FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.forLanguageTag("pl"))
    }
}
