package pl.barometr.alerts.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.alerts.internal.jooq.tables.references.DELIVERY_PREFERENCE
import pl.barometr.alerts.internal.jooq.tables.references.DIGEST
import pl.barometr.alerts.internal.jooq.tables.references.NOTIFICATION
import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.InterestKind
import pl.barometr.profiles.api.MatchedInterest
import pl.barometr.profiles.api.ProfileId
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Windows closing, against the schema — because the thing being tested is that nothing
 * falls between two of them.
 */
class DigestCloserTest {

    private val dsl = PostgresTestDatabase.dsl()

    // A Saturday at 10:00 UTC, which is noon in Warsaw: inside nobody's quiet hours and
    // after a morning boundary, so a test has to be explicit to get either.
    private val clock = TestClock()

    private val notifications = NotificationRepository(dsl, clock)
    private val digests = DigestRepository(dsl, clock)
    private val preferences = DeliveryPreferences(DeliveryPreferenceRepository(dsl, clock))
    private val queue = FakeJobQueue()
    private val closer = DigestCloser(
        notifications,
        digests,
        preferences,
        DigestSchedule(),
        DigestMailQueue(queue, JsonMapper.builder().addModule(kotlinModule()).build()),
        clock,
    )

    private val ewa = UserId.next()
    private val profile = ProfileId(Ids.next())

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(NOTIFICATION).execute()
        dsl.deleteFrom(DIGEST).execute()
        dsl.deleteFrom(DELIVERY_PREFERENCE).execute()
    }

    @Test
    fun `somebody who has said nothing hears as it happens`() {
        raise("a-1")

        assertTrue(closer.closeWindowFor(ewa))
        assertEquals(1, notifications.inDigest(digests.listFor(ewa, 1).single().id).size)
        assertTrue(notifications.waitingFor(ewa).isEmpty())
    }

    /** A mail saying "nothing happened" is the one nobody opens the next time. */
    /**
     * Queued in the same transaction as the digest, which is why the queue lives in
     * Postgres: no window in which a digest exists that nothing will ever send.
     */
    @Test
    fun `closing a window queues exactly one mail for it`() {
        raise("a-1")
        raise("a-2")

        closer.closeWindowFor(ewa)

        assertEquals(1, queue.jobs.size)
        assertEquals(DigestMailQueue.TYPE, queue.jobs.single().type)
    }

    @Test
    fun `a window that does not close queues nothing`() {
        preferences.set(ewa, DeliveryPreference(ewa, DeliveryMode.DAILY, atHour = 8))
        raise("a-1")

        closer.closeWindowFor(ewa)

        assertTrue(queue.jobs.isEmpty())
    }

    @Test
    fun `an empty buffer closes no window`() {
        assertEquals(false, closer.closeWindowFor(ewa))
        assertTrue(digests.listFor(ewa, 10).isEmpty())
    }

    @Test
    fun `a daily digest waits for its hour and then goes once`() {
        preferences.set(ewa, DeliveryPreference(ewa, DeliveryMode.DAILY, atHour = 8))
        raise("a-1")

        // Same day, after the eight o'clock boundary that this alert arrived too late for.
        assertEquals(false, closer.closeWindowFor(ewa))

        clock.advanceBy(Duration.ofHours(20))
        assertTrue(closer.closeWindowFor(ewa), "the next morning's boundary has passed")

        raise("a-2")
        assertEquals(false, closer.closeWindowFor(ewa), "the same day's boundary does not come round twice")
    }

    /**
     * The specification's acceptance criterion: switching cadence works from the next
     * window and loses nothing. It works because there is one buffer rather than one
     * per mode — a waiting notification says nothing about which cadence was in force
     * when it was raised.
     */
    @Test
    fun `switching from immediate to daily keeps what was already waiting`() {
        raise("a-1")
        raise("a-2")

        preferences.set(ewa, DeliveryPreference(ewa, DeliveryMode.DAILY, atHour = 8))
        assertEquals(false, closer.closeWindowFor(ewa))
        assertEquals(2, notifications.waitingFor(ewa).size, "nothing is dropped by the switch")

        clock.advanceBy(Duration.ofHours(20))
        assertTrue(closer.closeWindowFor(ewa))
        assertEquals(2, notifications.inDigest(digests.listFor(ewa, 1).single().id).size)
    }

    @Test
    fun `the quiet hours hold an ordinary alert until the morning`() {
        preferences.set(
            ewa,
            DeliveryPreference(ewa, DeliveryMode.IMMEDIATE, quiet = QuietHours(22, 7)),
        )
        // 23:00 in Warsaw.
        clock.advanceBy(Duration.ofHours(11))
        raise("a-1")

        assertEquals(false, closer.closeWindowFor(ewa))

        // 09:00 in Warsaw the next morning.
        clock.advanceBy(Duration.ofHours(10))
        assertTrue(closer.closeWindowFor(ewa))
    }

    /** "Wake me for this one" is the whole meaning of marking a rule critical. */
    @Test
    fun `a critical alert goes through the quiet hours`() {
        preferences.set(
            ewa,
            DeliveryPreference(ewa, DeliveryMode.IMMEDIATE, quiet = QuietHours(22, 7)),
        )
        clock.advanceBy(Duration.ofHours(11))
        raise("a-1", Urgency.CRITICAL)

        assertTrue(closer.closeWindowFor(ewa))
    }

    @Test
    fun `a critical alert does not wait for a weekly window, and the rest still do`() {
        preferences.set(ewa, DeliveryPreference(ewa, DeliveryMode.WEEKLY, atHour = 8, onWeekday = 1))
        raise("a-1")
        raise("a-2", Urgency.CRITICAL)

        assertTrue(closer.closeWindowFor(ewa))

        val digest = digests.listFor(ewa, 1).single()
        assertEquals(listOf("a-2"), notifications.inDigest(digest.id).map { it.subjectId })
        assertEquals(listOf("a-1"), notifications.waitingFor(ewa).map { it.subjectId })
    }

    @Test
    fun `a second window does not take back what the first one sent`() {
        raise("a-1")
        closer.closeWindowFor(ewa)

        clock.advanceBy(Duration.ofHours(1))
        raise("a-2")
        closer.closeWindowFor(ewa)

        val windows = digests.listFor(ewa, 10)
        assertEquals(2, windows.size)
        assertEquals(listOf("a-2"), notifications.inDigest(windows.first().id).map { it.subjectId })
        assertEquals(listOf("a-1"), notifications.inDigest(windows.last().id).map { it.subjectId })
    }

    @Test
    fun `a window holds only its own owner's alerts`() {
        val marek = UserId.next()
        raise("a-1")
        raise("a-2", owner = marek)

        closer.closeWindowFor(ewa)

        assertEquals(listOf("a-2"), notifications.waitingFor(marek).map { it.subjectId })
    }

    private fun raise(subject: String, urgency: Urgency = Urgency.NORMAL, owner: UserId = ewa) {
        notifications.raiseIfNew(
            owner,
            profile,
            1,
            ResolvedItem("act", subject, "Prawo budowlane", "DU/2024/$subject", stage = null),
            MatchedInterest(InterestKind.KEYWORD, "prawo budowlane"),
            urgency,
        )
    }
}
