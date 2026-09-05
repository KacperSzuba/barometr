package pl.barometr.alerts.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.alerts.internal.jooq.tables.references.CONSULTATION_FILING
import pl.barometr.identity.api.UserId
import pl.barometr.legislative.api.ConsultationId
import pl.barometr.legislative.api.DraftId
import pl.barometr.profiles.api.ProfileId
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * What one profile still has to answer.
 *
 * The matching itself is settled where it lives, against a real analyser, so here it is
 * an input; what is under test is everything around it — the window, the profile the
 * feed is about, and the consultations this person has already written in about.
 */
class ProfileDeadlinesTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val calendar = FakeConsultationCalendar()
    private val matching = FakeMatching()

    private lateinit var filings: ConsultationFilingRepository
    private lateinit var deadlines: ProfileDeadlines

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(CONSULTATION_FILING).execute()
        filings = ConsultationFilingRepository(dsl, clock)
        deadlines = ProfileDeadlines(calendar, matching, filings, CalendarProperties(baseUrl = "http://localhost"), clock)
    }

    @Test
    fun `a consultation the profile is interested in is in the feed, with the reason it was caught`() {
        val consultation = opens(closesOn = today().plusDays(10))
        matching.catches(consultation.draft.value.toString(), PROFILE, OWNER)

        val open = deadlines.openFor(PROFILE, OWNER)

        assertEquals(1, open.size)
        assertEquals("keyword", open.single().matchedKind)
        assertEquals("prawo budowlane", open.single().matchedValue)
    }

    @Test
    fun `a consultation nothing in this profile catches is not in the feed`() {
        opens(closesOn = today().plusDays(10))

        assertEquals(emptyList(), deadlines.openFor(PROFILE, OWNER))
    }

    @Test
    fun `another profile's interest does not put a consultation in this one's calendar`() {
        val consultation = opens(closesOn = today().plusDays(10))
        matching.catches(consultation.draft.value.toString(), ProfileId(Ids.next()), OWNER)

        assertEquals(emptyList(), deadlines.openFor(PROFILE, OWNER))
    }

    @Test
    fun `a consultation already written in about leaves the calendar`() {
        val consultation = opens(closesOn = today().plusDays(10))
        matching.catches(consultation.draft.value.toString(), PROFILE, OWNER)

        filings.recordFiling(OWNER, consultation.id, note = "uwagi wysłane 12 marca")

        assertEquals(emptyList(), deadlines.openFor(PROFILE, OWNER))
    }

    /**
     * A filing is one person's. Two subscribers watching the same draft have answered
     * it or not independently, and the ministry's record of who wrote in is not
     * something this system can see.
     */
    @Test
    fun `one subscriber's filing does not clear it from anybody else's calendar`() {
        val consultation = opens(closesOn = today().plusDays(10))
        matching.catches(consultation.draft.value.toString(), PROFILE, OWNER)
        filings.recordFiling(UserId(Ids.next()), consultation.id, note = null)

        assertEquals(1, deadlines.openFor(PROFILE, OWNER).size)
    }

    @Test
    fun `a consultation closing beyond the horizon is not in the feed yet`() {
        val consultation = opens(closesOn = today().plusDays(200))
        matching.catches(consultation.draft.value.toString(), PROFILE, OWNER)

        assertEquals(emptyList(), deadlines.openFor(PROFILE, OWNER))
    }

    @Test
    fun `what is left is counted in working days, not in calendar days`() {
        // The clock says Thursday 20 August 2026; the deadline is the following
        // Monday. Four calendar days, three of which anybody can write in on — and
        // "four days left" is how somebody ends up filing on a Sunday.
        val consultation = opens(closesOn = LocalDate.of(2026, 8, 24))
        matching.catches(consultation.draft.value.toString(), PROFILE, OWNER)

        assertEquals(3, deadlines.openFor(PROFILE, OWNER).single().workingDaysLeft)
    }

    private fun today() = LocalDate.now(clock)

    private fun opens(closesOn: LocalDate): OpenConsultation {
        val id = ConsultationId(Ids.next())
        val draft = DraftId(Ids.next())
        calendar.opens(id, draft, "Projekt ustawy o zmianie ustawy Prawo budowlane", closesOn)
        return OpenConsultation(id, draft)
    }

    private data class OpenConsultation(val id: ConsultationId, val draft: DraftId)

    private companion object {
        val OWNER = UserId(Ids.next())
        val PROFILE = ProfileId(Ids.next())
    }
}
