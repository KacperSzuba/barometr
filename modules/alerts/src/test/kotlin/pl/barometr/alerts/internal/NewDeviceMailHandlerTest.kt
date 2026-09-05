package pl.barometr.alerts.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.alerts.internal.jooq.tables.references.SUPPRESSED_ADDRESS
import pl.barometr.identity.api.SignedInFromNewDevice
import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.UserLookup
import pl.barometr.identity.api.UserSnapshot
import pl.barometr.platform.ClaimedJob
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The warning about a sign-in on a device nobody has used before.
 *
 * The rule worth pinning down is the one that looks like a bug until it is read twice:
 * an unsubscribe does **not** stop this message. Somebody who asked to stop receiving
 * digests asked about digests, and "stop telling me when my password is used somewhere
 * new" is not a preference this product offers.
 */
class NewDeviceMailHandlerTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private val queue = FakeJobQueue()
    private val mails = NewDeviceMailQueue(queue, json)
    private val transport = RecordingTransport()
    private val meters = SimpleMeterRegistry()

    private lateinit var suppressions: SuppressionRepository
    private lateinit var handler: NewDeviceMailHandler

    private val ewa = KnownUsers.ewa

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(SUPPRESSED_ADDRESS).execute()
        suppressions = SuppressionRepository(dsl, clock)
        handler = NewDeviceMailHandler(mails, suppressions, KnownUsers, NewDeviceMail(), meters, transport)
    }

    @Test
    fun `the warning says what happened, when, and what to do about it`() {
        handler.handle(job(signIn()))

        val sent = transport.sent.single()
        assertEquals("ewa@example.test", sent.to)
        assertTrue(sent.subject.contains("Nowe logowanie"))
        assertTrue(sent.text.contains("Mozilla/5.0 (iPhone)"))
        assertTrue(sent.text.contains("203.0.113.7"))
        assertTrue(sent.text.contains("Warszawa, PL"), "where it was, roughly, is what somebody recognises")
        assertTrue(sent.text.contains("zmień hasło"), sent.text)
    }

    /** The one message in this system that carries no way to stop it. */
    @Test
    fun `the warning carries no unsubscribe link`() {
        handler.handle(job(signIn()))

        assertEquals(null, transport.sent.single().unsubscribeUrl)
    }

    @Test
    fun `an unsubscribe does not stop a security warning`() {
        suppressions.suppress("ewa@example.test", SuppressionReason.UNSUBSCRIBED)

        handler.handle(job(signIn()))

        assertEquals(1, transport.sent.size)
    }

    /** A dead address does stop it: the message will not arrive, and trying costs reputation. */
    @Test
    fun `a bounced address is not written to again`() {
        suppressions.suppress("ewa@example.test", SuppressionReason.BOUNCED)

        handler.handle(job(signIn()))

        assertEquals(emptyList(), transport.sent)
        assertEquals(1.0, meters.counter("alerts.new_device.unsent", "reason", "bounced").count())
    }

    @Test
    fun `a complaint stops it too`() {
        suppressions.suppress("ewa@example.test", SuppressionReason.COMPLAINED)

        handler.handle(job(signIn()))

        assertEquals(emptyList(), transport.sent)
    }

    @Test
    fun `a warning for an account that is gone is counted, not retried forever`() {
        handler.handle(job(signIn(user = UserId(Ids.next()))))

        assertEquals(emptyList(), transport.sent)
        assertEquals(1.0, meters.counter("alerts.new_device.unsent", "reason", "no-account").count())
    }

    /**
     * A developer machine has no mail server, and a security warning that quietly
     * counted as delivered would be worse than one that fails.
     */
    @Test
    fun `with no mail server the job fails rather than reporting success`() {
        val without = NewDeviceMailHandler(mails, suppressions, KnownUsers, NewDeviceMail(), meters, transport = null)

        assertFailsWith<IllegalStateException> { without.handle(job(signIn())) }
    }

    @Test
    fun `the same sign-in is queued once, however often the event is redelivered`() {
        val signIn = signIn()

        assertTrue(mails.queueWarning(signIn))
        assertTrue(!mails.queueWarning(signIn), "the session is the dedup key")
    }

    private fun signIn(user: UserId = ewa) = SignedInFromNewDevice(
        userId = user,
        sessionId = Ids.next(),
        userAgent = "Mozilla/5.0 (iPhone)",
        clientIp = "203.0.113.7",
        approximateLocation = "Warszawa, PL",
        occurredAt = Instant.parse("2026-03-30T12:20:00Z"),
    )

    /** The job as the worker hands it over, with the payload the queue actually holds. */
    private fun job(signIn: SignedInFromNewDevice): ClaimedJob {
        mails.queueWarning(signIn)

        return ClaimedJob(
            id = Ids.next(),
            type = NewDeviceMailQueue.TYPE,
            payload = queue.jobs.last().payload,
            attempt = 1,
            maxAttempts = 5,
        )
    }

    /** One account, addressed the way the real lookup addresses one. */
    private object KnownUsers : UserLookup {
        val ewa = UserId(Ids.next())

        override fun findById(id: UserId): UserSnapshot? =
            UserSnapshot(id, "ewa@example.test", emptySet(), enabled = true).takeIf { id == ewa }

        override fun findByEmail(email: String): UserSnapshot? =
            findById(ewa).takeIf { email == "ewa@example.test" }
    }

    private class RecordingTransport : EmailTransport {
        val sent = mutableListOf<EmailMessage>()

        override fun send(message: EmailMessage) {
            sent += message
        }
    }
}
