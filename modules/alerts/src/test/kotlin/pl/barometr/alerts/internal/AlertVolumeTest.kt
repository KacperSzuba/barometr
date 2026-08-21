package pl.barometr.alerts.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.alerts.internal.jooq.tables.references.ALERT_DECISION
import pl.barometr.alerts.internal.jooq.tables.references.ALERT_RULE
import pl.barometr.alerts.internal.jooq.tables.references.NOTIFICATION
import pl.barometr.alerts.internal.jooq.tables.references.PENDING_ITEM
import pl.barometr.identity.api.UserId
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeKind
import pl.barometr.profiles.api.InterestKind
import pl.barometr.profiles.api.ProfileId
import pl.barometr.shared.Eli
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The specification's acceptance criterion, run: a week of real-shaped traffic must
 * leave a typical profile with fewer than ten notifications a day.
 *
 * The shape is what makes it a test rather than an arithmetic exercise. Ingest does not
 * deliver a tidy stream of new things — every cycle re-derives what it read, so the
 * same sixty acts and ten drafts arrive four times a day, seven days running. Five
 * hundred and thirty-two matches go in. What comes out is what deduplication is for,
 * and if any of the three rules stopped working this number would leave the range at
 * once.
 */
class AlertVolumeTest {

    private val dsl = PostgresTestDatabase.dsl()
    private val clock = TestClock()

    private val catalog = FakeCatalog()
    private val matching = FakeMatching()
    private val pending = PendingItemRepository(dsl, clock)
    private val notifications = NotificationRepository(dsl, clock)

    private val run = AlertMatchRun(
        pending,
        BufferedItemReader(catalog),
        AlertRaiser(matching, AlertRuleRepository(dsl, clock), notifications, AlertDecisionRepository(dsl, clock), clock),
    )

    private val ewa = UserId.next()
    private val profile = ProfileId(Ids.next())

    private val acts = List(ACTS) { ActId(Ids.next()) }
    private val drafts = List(DRAFTS) { DraftId(Ids.next()) }

    @BeforeEach
    fun setUp() {
        listOf(ALERT_DECISION, NOTIFICATION, ALERT_RULE, PENDING_ITEM).forEach { dsl.deleteFrom(it).execute() }
        AlertRuleRepository(dsl, clock).create(ewa, profile, stages = emptySet())

        acts.forEachIndexed { i, id -> catalog.publish(id, "Ustawa numer $i", Eli.of("DU", 2026, i + 1)) }
        drafts.forEachIndexed { i, id -> catalog.track(id, "Projekt numer $i", FIRST_STAGE) }

        // A typical profile: a quarter of the Journal is in its area, and it follows a
        // handful of drafts through the Sejm.
        acts.take(MATCHED_ACTS).forEach {
            matching.catches(it.value.toString(), profile, ewa, InterestKind.KEYWORD, "prawo budowlane")
        }
        drafts.take(MATCHED_DRAFTS).forEach {
            matching.catches(it.value.toString(), profile, ewa, InterestKind.PKD, "41.20.Z")
        }
    }

    @Test
    fun `a week of traffic leaves a typical profile under ten notifications a day`() {
        repeat(DAYS) { day ->
            // Two of the followed drafts genuinely move, on two separate days.
            if (day == 1 || day == 4) {
                drafts.forEach { catalog.track(it, "Projekt", "stage-$day") }
            }

            repeat(CYCLES_A_DAY) {
                everythingIsRederived()
                run.raiseWaitingAlerts()
                clock.advanceBy(Duration.ofHours(HOURS_A_DAY / CYCLES_A_DAY))
            }
        }

        val told = notifications.listFor(ewa, 1_000)

        assertTrue(told.size < DAYS * DAILY_LIMIT, "a week gave ${told.size} notifications")
        // And not by being silent: every act in the profile's area was reported once,
        // and every followed draft at least once.
        assertTrue(told.size >= MATCHED_ACTS + MATCHED_DRAFTS, "only ${told.size} notifications")
        assertEquals(MATCHED_ACTS, told.count { it.subjectKind == LegislativeKind.ACT })
    }

    /** What a cycle actually does: re-derive everything it read, changed or not. */
    private fun everythingIsRederived() {
        acts.forEach { pending.append(LegislativeKind.ACT, it.value.toString()) }
        drafts.forEach { pending.append(LegislativeKind.DRAFT, it.value.toString()) }
    }

    private companion object {
        const val DAYS = 7
        const val CYCLES_A_DAY = 4
        const val HOURS_A_DAY = 24L
        const val ACTS = 60
        const val DRAFTS = 10
        const val MATCHED_ACTS = 15
        const val MATCHED_DRAFTS = 4
        const val FIRST_STAGE = "i_reading"

        /** The number in the specification. */
        const val DAILY_LIMIT = 10
    }
}
