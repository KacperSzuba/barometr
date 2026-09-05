package pl.barometr.alerts.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.alerts.internal.jooq.tables.references.ALERT_DECISION
import pl.barometr.alerts.internal.jooq.tables.references.NOTIFICATION
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Retention as a thing that runs: what is past its keeping goes, and what is not stays.
 *
 * Against a real database because the whole operation is one `DELETE … WHERE`, and a fake
 * would be testing the fake's understanding of `<`.
 */
class AlertRetentionSweepTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val properties = AlertRetentionProperties()

    private lateinit var sweep: AlertRetentionSweep

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(NOTIFICATION).execute()
        dsl.deleteFrom(ALERT_DECISION).execute()
        sweep = AlertRetentionSweep(dsl, properties, SimpleMeterRegistry(), clock)
    }

    @Test
    fun `a notification past its keeping is deleted, and a recent one is not`() {
        notification(age = properties.notifications.plus(Duration.ofDays(1)))
        notification(age = Duration.ofDays(30))

        sweep.deleteWhatRetentionSaysToDelete()

        assertEquals(1, dsl.fetchCount(NOTIFICATION))
    }

    /**
     * Decisions go sooner than notifications: the engine's record of why somebody was
     * *not* told answers support's first question and stops being interesting long before
     * what they were told does.
     */
    @Test
    fun `a decision is kept for less time than a notification`() {
        val age = properties.decisions.plus(Duration.ofDays(1))
        notification(age = age)
        decision(age = age)

        sweep.deleteWhatRetentionSaysToDelete()

        assertEquals(1, dsl.fetchCount(NOTIFICATION), "still inside two years")
        assertEquals(0, dsl.fetchCount(ALERT_DECISION))
    }

    @Test
    fun `a sweep with nothing to do changes nothing`() {
        notification(age = Duration.ofDays(1))

        sweep.deleteWhatRetentionSaysToDelete()

        assertEquals(1, dsl.fetchCount(NOTIFICATION))
    }

    private fun notification(age: Duration) {
        dsl.insertInto(NOTIFICATION)
            .set(NOTIFICATION.ID, Ids.next())
            .set(NOTIFICATION.OWNER_ID, UUID.randomUUID())
            .set(NOTIFICATION.PROFILE_ID, UUID.randomUUID())
            .set(NOTIFICATION.PROFILE_VERSION, 1)
            .set(NOTIFICATION.SUBJECT_KIND, "draft")
            .set(NOTIFICATION.SUBJECT_ID, UUID.randomUUID().toString())
            .set(NOTIFICATION.TITLE, "Projekt ustawy")
            .set(NOTIFICATION.MATCHED_KIND, "keyword")
            .set(NOTIFICATION.MATCHED_VALUE, "prawo budowlane")
            .set(NOTIFICATION.EVENT_KEY, Ids.next().toString())
            .set(NOTIFICATION.CASE_KEY, Ids.next().toString())
            .set(NOTIFICATION.URGENCY, Urgency.NORMAL.wireName)
            .set(NOTIFICATION.SIGNIFICANCE, 50)
            .set(NOTIFICATION.SIGNIFICANCE_REASONS, arrayOf("close-to-enactment"))
            .set(NOTIFICATION.CREATED_AT, clock.instant().minus(age).atOffset(ZoneOffset.UTC))
            .execute()
    }

    private fun decision(age: Duration) {
        dsl.insertInto(ALERT_DECISION)
            .set(ALERT_DECISION.ID, Ids.next())
            .set(ALERT_DECISION.OWNER_ID, UUID.randomUUID())
            .set(ALERT_DECISION.PROFILE_ID, UUID.randomUUID())
            .set(ALERT_DECISION.SUBJECT_KIND, "draft")
            .set(ALERT_DECISION.SUBJECT_ID, UUID.randomUUID().toString())
            .set(ALERT_DECISION.EVENT_KEY, Ids.next().toString())
            .set(ALERT_DECISION.DECISION, "raised")
            .set(ALERT_DECISION.REASON, "raised")
            .set(ALERT_DECISION.DECIDED_AT, clock.instant().minus(age).atOffset(ZoneOffset.UTC))
            .execute()
    }
}
