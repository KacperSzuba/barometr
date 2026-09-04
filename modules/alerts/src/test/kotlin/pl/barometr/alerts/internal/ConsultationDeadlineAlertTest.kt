package pl.barometr.alerts.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.alerts.internal.jooq.tables.references.ALERT_DECISION
import pl.barometr.alerts.internal.jooq.tables.references.ALERT_RULE
import pl.barometr.alerts.internal.jooq.tables.references.NOTIFICATION
import pl.barometr.alerts.internal.jooq.tables.references.PENDING_ITEM
import pl.barometr.identity.api.UserId
import pl.barometr.legislative.api.ConsultationId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeKind
import pl.barometr.profiles.api.InterestKind
import pl.barometr.profiles.api.ProfileId
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Being told, while there is still time to write in.
 *
 * The whole path in one place, because the value of this alert is in the joins between
 * its parts: a calendar the watch reads, a buffer it writes to, the run that decides
 * who hears, and the keys that stop a reader hearing it four times a day for three
 * days. Any one of those tested alone would pass while the product told somebody
 * nothing.
 */
class ConsultationDeadlineAlertTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)

    /**
     * A Tuesday. Counting the day itself, three working days from here reach the
     * Thursday — Tuesday, Wednesday, Thursday — which is what "three days left" means
     * to somebody deciding whether they still have time to write something.
     */
    private val clock = TestClock(Instant.parse("2026-04-21T07:00:00Z"))

    private val calendar = FakeConsultationCalendar()
    private val catalog = FakeCatalog()
    private val matching = FakeMatching()

    private val pending = PendingItemRepository(dsl, clock)
    private val notifications = NotificationRepository(dsl, clock)

    private val watch = ConsultationDeadlineWatch(calendar, pending, clock)
    private val run = AlertMatchRun(
        pending,
        BufferedItemReader(catalog, calendar, clock),
        AlertRaiser(
            matching,
            SignificanceScale(clock),
            AlertRuleRepository(dsl, clock),
            notifications,
            AlertDecisionRepository(dsl, clock),
            clock,
        ),
    )

    private val ewa = UserId.next()
    private val profile = ProfileId(Ids.next())
    private val draft = DraftId(Ids.next())
    private val consultation = ConsultationId(Ids.next())

    @BeforeEach
    fun setUp() {
        listOf(ALERT_DECISION, NOTIFICATION, ALERT_RULE, PENDING_ITEM).forEach { dsl.deleteFrom(it).execute() }
        AlertRuleRepository(dsl, clock).create(ewa, profile, stages = emptySet())

        catalog.track(draft, TITLE, STAGE)
        matching.catches(draft.value.toString(), profile, ewa, InterestKind.DRAFT, draft.value.toString())
    }

    @Test
    fun `a consultation closing in three working days reaches the person watching the draft`() {
        calendar.opens(consultation, draft, TITLE, closesOn = THURSDAY)

        watch.bufferConsultationsClosingSoon()
        run.raiseWaitingAlerts()

        val told = notifications.listFor(ewa, 10).single()
        assertEquals(TITLE, told.title, "the draft is what they subscribed to, and what they are told about")
        assertEquals(LegislativeKind.DRAFT, told.subjectKind)
        assertEquals(draft.value.toString(), told.subjectId)
        assertEquals(THURSDAY, told.closesOn, "the day they have to write by")
    }

    /**
     * A consultation sits inside the warning window for its last three working days,
     * and the watch runs four times a day. Twelve runs, one alert.
     */
    @Test
    fun `a consultation seen on every run for three days is one alert`() {
        calendar.opens(consultation, draft, TITLE, closesOn = THURSDAY)

        repeat(WATCHES_A_DAY * 3) {
            watch.bufferConsultationsClosingSoon()
            run.raiseWaitingAlerts()
            clock.advanceBy(Duration.ofHours(24L / WATCHES_A_DAY))
        }

        assertEquals(1, notifications.listFor(ewa, 10).size)
    }

    /**
     * The ministry moving the date is the news this reader most needs after the first
     * warning: they had planned around the old day.
     */
    @Test
    fun `a consultation extended states a new deadline and is told again`() {
        calendar.opens(consultation, draft, TITLE, closesOn = THURSDAY)
        watch.bufferConsultationsClosingSoon()
        run.raiseWaitingAlerts()

        calendar.extends(consultation, to = EXTENDED_TO)
        // The Friday: three working days from it are Friday, Monday and the Tuesday the
        // consultation now closes on, so the extended window is warned about in turn.
        clock.advanceBy(Duration.ofDays(3))
        watch.bufferConsultationsClosingSoon()
        run.raiseWaitingAlerts()

        assertEquals(
            listOf(THURSDAY, EXTENDED_TO),
            notifications.listFor(ewa, 10).mapNotNull { it.closesOn }.sorted(),
        )
    }

    /**
     * A deadline is a matter of its own. Somebody told this morning that the draft
     * moved must still hear that they have three days left to say something about it —
     * the twenty-four-hour rule is there to stop repetition, not to swallow the one
     * alert that expires.
     */
    @Test
    fun `hearing about the draft this morning does not silence the deadline`() {
        pending.append(LegislativeKind.DRAFT, draft.value.toString())
        run.raiseWaitingAlerts()

        calendar.opens(consultation, draft, TITLE, closesOn = THURSDAY)
        clock.advanceBy(Duration.ofHours(2))
        watch.bufferConsultationsClosingSoon()
        run.raiseWaitingAlerts()

        val told = notifications.listFor(ewa, 10)
        assertEquals(2, told.size, "the draft moving and the window closing are two things to know")
        assertEquals(listOf(THURSDAY), told.mapNotNull { it.closesOn })
    }

    /**
     * The three warnings, and the whole point of there being three: a month out there
     * is still time to read the draft and ask somebody, a fortnight out it has been
     * forgotten, and three days out is when it gets done. What must not happen is a
     * fourth — the same deadline mentioned every morning until it passes is what makes
     * people turn alerts off.
     */
    @Test
    fun `a consultation is warned about three times on its way to closing`() {
        calendar.opens(consultation, draft, TITLE, closesOn = LocalDate.of(2026, 6, 30))

        repeat(WATCHED_DAYS) {
            watch.bufferConsultationsClosingSoon()
            run.raiseWaitingAlerts()
            clock.advanceBy(Duration.ofDays(1))
        }

        assertEquals(
            ConsultationWarnings.MARKS.map { "consultation:$consultation@2026-06-30#$it" },
            dsl.select(NOTIFICATION.EVENT_KEY)
                .from(NOTIFICATION)
                .orderBy(NOTIFICATION.CREATED_AT)
                .fetch { it.value1() },
            "one warning per band, in the order the bands arrive",
        )
    }

    /** Ten weeks out is nobody's news yet, and a heads-up nobody acts on is noise. */
    @Test
    fun `a consultation further off than the first warning says nothing`() {
        calendar.opens(consultation, draft, TITLE, closesOn = LocalDate.of(2026, 8, 31))

        watch.bufferConsultationsClosingSoon()
        run.raiseWaitingAlerts()

        assertEquals(0, notifications.listFor(ewa, 10).size)
    }

    /**
     * A band the system was down through is a warning that has passed, not one to catch
     * up on: telling somebody they have a fortnight when they have three days would be
     * worse than not writing at all. What is owed is the band they are in now, and that
     * one is still sent.
     */
    @Test
    fun `a run down through a band sends the warning that is owed now, not the one it missed`() {
        calendar.opens(consultation, draft, TITLE, closesOn = LocalDate.of(2026, 6, 30))

        // The fifth of June: eighteen working days short of the deadline, and so inside
        // the first band.
        clock.advanceBy(Duration.ofDays(45))
        watch.bufferConsultationsClosingSoon()
        run.raiseWaitingAlerts()

        // Nothing runs for three weeks. What comes back up is looking at two working
        // days left, with the fortnight's warning long past.
        clock.advanceBy(Duration.ofDays(22))
        watch.bufferConsultationsClosingSoon()
        run.raiseWaitingAlerts()

        assertEquals(
            listOf("consultation:$consultation@2026-06-30#20", "consultation:$consultation@2026-06-30#3"),
            dsl.select(NOTIFICATION.EVENT_KEY)
                .from(NOTIFICATION)
                .orderBy(NOTIFICATION.CREATED_AT)
                .fetch { it.value1() },
        )
    }

    /**
     * The date is read when the item is judged, not when it was buffered: a ministry can
     * extend a consultation in the gap, and a warning naming the old day would be the
     * one thing this alert must never get wrong.
     */
    @Test
    fun `a deadline moved between the watch and the run is told as it now stands`() {
        calendar.opens(consultation, draft, TITLE, closesOn = THURSDAY)
        watch.bufferConsultationsClosingSoon()

        calendar.extends(consultation, to = THURSDAY.plusWeeks(2))
        run.raiseWaitingAlerts()

        assertEquals(THURSDAY.plusWeeks(2), notifications.listFor(ewa, 10).single().closesOn)
    }

    @Test
    fun `a consultation nobody is watching tells nobody`() {
        val unwatched = DraftId(Ids.next())
        catalog.track(unwatched, "Projekt rozporządzenia w sprawie opłat", STAGE)
        calendar.opens(ConsultationId(Ids.next()), unwatched, "Projekt rozporządzenia", closesOn = THURSDAY)

        watch.bufferConsultationsClosingSoon()
        run.raiseWaitingAlerts()

        assertEquals(0, notifications.listFor(ewa, 10).size)
        assertTrue(
            dsl.fetchCount(PENDING_ITEM) > 0,
            "it was still judged: an item left waiting would be re-read by every later run",
        )
    }

    /**
     * A consultation the archive can no longer describe — the draft withdrawn, the row
     * gone — is judged and dropped rather than left to be re-read for ever.
     */
    @Test
    fun `a consultation the calendar no longer knows is judged and passed over`() {
        pending.append(ConsultationNotice.KIND, ConsultationId(Ids.next()).value.toString())

        run.raiseWaitingAlerts()

        assertEquals(0, notifications.listFor(ewa, 10).size)
        assertNotNull(
            dsl.selectFrom(PENDING_ITEM).fetchOne()?.processedAt,
            "left waiting, it would be read again by every run there is",
        )
    }

    private companion object {
        const val TITLE = "Projekt ustawy o zmianie ustawy o kredycie konsumenckim"
        const val STAGE = "government_process"
        const val WATCHES_A_DAY = 4

        /** From the Tuesday the clock starts on to a deadline at the end of June. */
        const val WATCHED_DAYS = 70

        /** Three working days from the Tuesday the clock starts on, counting both ends. */
        val THURSDAY: LocalDate = LocalDate.of(2026, 4, 23)

        /** One working day further, and so one day too early to warn about. */
        val FRIDAY: LocalDate = LocalDate.of(2026, 4, 24)

        /** Where a ministry's extension puts the deadline: the Tuesday after. */
        val EXTENDED_TO: LocalDate = LocalDate.of(2026, 4, 28)
    }
}
