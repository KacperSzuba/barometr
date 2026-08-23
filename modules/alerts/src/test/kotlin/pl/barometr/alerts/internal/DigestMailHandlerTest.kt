package pl.barometr.alerts.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.alerts.internal.jooq.tables.references.DIGEST
import pl.barometr.alerts.internal.jooq.tables.references.EMAIL_DELIVERY
import pl.barometr.alerts.internal.jooq.tables.references.NOTIFICATION
import pl.barometr.alerts.internal.jooq.tables.references.SUPPRESSED_ADDRESS
import pl.barometr.alerts.internal.jooq.tables.references.UNSUBSCRIBE_TOKEN
import pl.barometr.identity.api.Role
import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.UserLookup
import pl.barometr.identity.api.UserSnapshot
import pl.barometr.platform.ClaimedJob
import pl.barometr.profiles.api.InterestKind
import pl.barometr.profiles.api.MatchedInterest
import pl.barometr.profiles.api.ProfileId
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Sending one digest — and, mostly, the four ways it can end without a message going
 * out. Each of those is written down, because a digest nobody sent and nobody can
 * explain is indistinguishable from a quiet week.
 */
class DigestMailHandlerTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()

    private val digests = DigestRepository(dsl, clock)
    private val notifications = NotificationRepository(dsl, clock)
    private val deliveries = EmailDeliveryRepository(dsl, clock)
    private val suppressions = SuppressionRepository(dsl, clock)
    private val tokens = UnsubscribeTokenRepository(dsl, clock)
    private val mails = DigestMailQueue(FakeJobQueue(), json)
    private val users = FakeUsers()
    private val post = RecordingTransport()

    private val handler = DigestMailHandler(
        mails, digests, notifications, deliveries, suppressions, tokens, users,
        DigestMail(),
        EmailProperties(from = "alerty@barometr.example", unsubscribeBaseUrl = "https://barometr.example"),
        post,
    )

    private val ewa = UserId.next()

    @BeforeEach
    fun setUp() {
        listOf(EMAIL_DELIVERY, NOTIFICATION, DIGEST, SUPPRESSED_ADDRESS, UNSUBSCRIBE_TOKEN)
            .forEach { dsl.deleteFrom(it).execute() }
        users.knows(ewa, "ewa@example.com")
        post.forget()
    }

    @Test
    fun `a closed window reaches the address on the account`() {
        val digest = closedWindowWith("Prawo budowlane")

        handler.handle(jobFor(digest))

        val sent = post.sent.single()
        assertEquals("ewa@example.com", sent.to)
        assertTrue(sent.text.contains("Prawo budowlane"))
        assertEquals(DeliveryStatus.SENT, deliveries.statusOf(digest))
    }

    /** The link has to work from an old message, so the token is the person's, not the window's. */
    @Test
    fun `the unsubscribe link is the same one in every message`() {
        val first = closedWindowWith("Prawo budowlane")
        val second = closedWindowWith("Prawo wodne")

        handler.handle(jobFor(first))
        handler.handle(jobFor(second))

        assertEquals(post.sent.first().unsubscribeUrl, post.sent.last().unsubscribeUrl)
        assertTrue(post.sent.first().unsubscribeUrl.startsWith("https://barometr.example/api/v1/alerts/unsubscribe/"))
    }

    /**
     * The queue delivers at least once, so a retry after the transport accepted a
     * message but before the row was written must not send it twice — a duplicate
     * digest reads as a bug to the person receiving it.
     */
    @Test
    fun `a digest that already went out is not sent again`() {
        val digest = closedWindowWith("Prawo budowlane")

        handler.handle(jobFor(digest))
        handler.handle(jobFor(digest))

        assertEquals(1, post.sent.size)
    }

    @Test
    fun `an address that bounced is recorded and left alone`() {
        suppressions.suppress("ewa@example.com", SuppressionReason.BOUNCED, "550 no such user")
        val digest = closedWindowWith("Prawo budowlane")

        handler.handle(jobFor(digest))

        assertTrue(post.sent.isEmpty())
        assertEquals(DeliveryStatus.SUPPRESSED, deliveries.statusOf(digest))
    }

    /**
     * Recorded and rethrown: the row says what happened and the queue decides whether
     * to try again. Swallowing it would turn a full mailbox into silence.
     */
    @Test
    fun `a transport that refuses is written down and thrown on`() {
        val digest = closedWindowWith("Prawo budowlane")
        post.refuse("mailbox full")

        assertFailsWith<IllegalStateException> { handler.handle(jobFor(digest)) }

        assertEquals(DeliveryStatus.FAILED, deliveries.statusOf(digest))
    }

    @Test
    fun `an account that no longer exists fails the delivery rather than the run`() {
        val digest = closedWindowWith("Prawo budowlane")
        users.forgets(ewa)

        handler.handle(jobFor(digest))

        assertEquals(DeliveryStatus.FAILED, deliveries.statusOf(digest))
        assertTrue(post.sent.isEmpty())
    }

    @Test
    fun `a digest that has been deleted is not an error`() {
        handler.handle(jobFor(UUID.randomUUID()))

        assertTrue(post.sent.isEmpty())
    }

    /**
     * With no mail server configured there is no transport, and the job fails loudly.
     * A digest that silently counted as delivered would be a lie told once per window.
     */
    @Test
    fun `no configured transport is a failure, not a quiet success`() {
        val withoutMail = DigestMailHandler(
            mails, digests, notifications, deliveries, suppressions, tokens, users,
            DigestMail(),
            EmailProperties(from = "alerty@barometr.example", unsubscribeBaseUrl = "https://barometr.example"),
            transport = null,
        )
        val digest = closedWindowWith("Prawo budowlane")

        assertFailsWith<IllegalStateException> { withoutMail.handle(jobFor(digest)) }
        // Recorded as failed rather than passed over: a digest with no delivery row at
        // all looks like one nothing has got to yet, which is the state this is not.
        assertEquals(DeliveryStatus.FAILED, deliveries.statusOf(digest))
    }

    private fun closedWindowWith(title: String): UUID {
        notifications.raiseIfNew(
            ewa,
            ProfileId(Ids.next()),
            1,
            ResolvedItem("act", UUID.randomUUID().toString(), title, "DU/2024/1", stage = null, signals = null),
            MatchedInterest(InterestKind.KEYWORD, "prawo"),
            Urgency.NORMAL,
            Significance(0, emptyList()),
        )
        val digest = digests.open(ewa)
        notifications.attachTo(digest, notifications.waitingFor(ewa))
        return digest.id
    }

    private fun jobFor(digest: UUID) = ClaimedJob(
        id = Ids.next(),
        type = DigestMailQueue.TYPE,
        payload = json.writeValueAsString(DigestMailQueue.WirePayload(digest.toString())),
        attempt = 1,
        maxAttempts = 5,
    )

    private class FakeUsers : UserLookup {
        private val known = mutableMapOf<UserId, UserSnapshot>()

        fun knows(id: UserId, email: String) {
            known[id] = UserSnapshot(id, email, setOf(Role.USER), enabled = true)
        }

        fun forgets(id: UserId) = known.remove(id)

        override fun findById(id: UserId) = known[id]

        override fun findByEmail(email: String) = known.values.firstOrNull { it.email == email }
    }

    private class RecordingTransport : EmailTransport {
        private val messages = mutableListOf<EmailMessage>()
        private var refusal: String? = null

        val sent: List<EmailMessage> get() = messages

        fun forget() {
            messages.clear()
            refusal = null
        }

        fun refuse(why: String) {
            refusal = why
        }

        override fun send(message: EmailMessage) {
            refusal?.let { error(it) }
            messages.add(message)
        }
    }
}
