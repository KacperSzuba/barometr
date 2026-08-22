package pl.barometr.testing

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

/**
 * A real mail server for the tests that send to one.
 *
 * The part of an outgoing message that decides whether it reaches an inbox is its
 * headers, and a fake `JavaMailSender` would let every one of them be wrong while the
 * test passed. Mailpit accepts SMTP like anything else and hands the message back over
 * HTTP, so what is asserted is what a mail server received.
 *
 * Shared and started once, like the Postgres beside it.
 */
object MailTestServer {

    private const val SMTP_PORT = 1025
    private const val HTTP_PORT = 8025

    private val container: GenericContainer<*> by lazy {
        GenericContainer("axllent/mailpit:v1.21")
            .withExposedPorts(SMTP_PORT, HTTP_PORT)
            .waitingFor(Wait.forHttp("/api/v1/messages").forPort(HTTP_PORT))
            .also { it.start() }
    }

    val host: String get() = container.host

    val smtpPort: Int get() = container.getMappedPort(SMTP_PORT)

    /** Where the messages it accepted can be read back. */
    val apiBaseUrl: String get() = "http://${container.host}:${container.getMappedPort(HTTP_PORT)}"
}
