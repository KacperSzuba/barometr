package pl.barometr.legislative.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.legislative.api.ConsultationId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.CATALOG_FOLDER
import pl.barometr.legislative.internal.jooq.tables.references.CONSULTATION
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the rest of the system may ask about consultations: which close in a window,
 * and what one of them says.
 *
 * The window is answered rather than a page, because every caller — a calendar, the
 * run that warns somebody three days out — asks about a range of days, and because a
 * consultation with no date must be invisible to all of them rather than appear with
 * an empty field where the deadline goes.
 */
class ConsultationCalendarTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private val drafts = DraftRepository(dsl, clock)
    private val consultations = ConsultationRepository(dsl, clock)
    private val calendar = ConsultationCalendarAdapter(dsl)

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(CATALOG_FOLDER).execute()
        dsl.deleteFrom(CONSULTATION).execute()
        dsl.deleteFrom(DRAFT).execute()
    }

    @Test
    fun `consultations closing in the window come back soonest first`() {
        dated("Projekt ustawy o kredycie konsumenckim", LocalDate.of(2026, 5, 20))
        dated("Projekt ustawy o dostępie do informacji publicznej", LocalDate.of(2026, 5, 4))
        dated("Projekt rozporządzenia w sprawie opłat", LocalDate.of(2026, 6, 30))

        val closing = calendar.closingBetween(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31))

        assertEquals(
            listOf(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 20)),
            closing.map { it.closesOn },
            "the one closing in June is outside the window",
        )
    }

    @Test
    fun `the window includes the days at both ends`() {
        dated("Projekt ustawy o kredycie konsumenckim", LocalDate.of(2026, 5, 4))

        val onTheDay = calendar.closingBetween(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 4))

        assertEquals(1, onTheDay.size, "a consultation closing today is the one a reader most needs")
    }

    /**
     * A consultation whose letter has not been filed or could not be read is a real
     * thing this system knows about and has nothing to tell anybody about yet. Putting
     * it in a calendar with a guessed deadline is the failure the whole provenance
     * chain exists to prevent.
     */
    @Test
    fun `a consultation nothing has dated is in no window`() {
        val undated = consultations.openConsultation(draft("Projekt ustawy o zmianie ustawy"), "13196866")

        assertEquals(
            emptyList(),
            calendar.closingBetween(LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1)),
        )
        assertNull(calendar.consultationById(undated), "nor is it answerable one at a time")
    }

    @Test
    fun `a consultation carries the ministry's own sentence beside the date`() {
        val id = dated("Projekt ustawy o kredycie konsumenckim", LocalDate.of(2026, 4, 30))

        val deadline = assertNotNull(calendar.consultationById(id))
        assertEquals("Projekt ustawy o kredycie konsumenckim", deadline.draftTitle)
        assertEquals(LocalDate.of(2026, 4, 9), deadline.opensOn)
        assertEquals(21, deadline.daysAllowed)
        assertEquals(QUOTE, deadline.quote)
        assertEquals("konsultacje@ms.gov.pl", deadline.submissionAddress)
    }

    /**
     * A ministry rewrites a draft's title while it is out for comment. The entry names
     * what the register says now, because it is joined rather than copied.
     */
    @Test
    fun `a renamed draft renames its calendar entry`() {
        val id = dated("Projekt ustawy o kredycie konsumenckim", LocalDate.of(2026, 4, 30))
        val draftId = assertNotNull(calendar.consultationById(id)).draftId
        drafts.restateDraft(
            draftId,
            DraftFromRegister(
                title = "Projekt ustawy o zmianie ustawy o kredycie konsumenckim",
                initiator = DraftInitiator.GOVERNMENT,
                term = 10,
                startedOn = LocalDate.of(2026, 4, 9),
            ),
        )

        assertEquals(
            "Projekt ustawy o zmianie ustawy o kredycie konsumenckim",
            calendar.consultationById(id)?.draftTitle,
        )
    }

    @Test
    fun `an identifier nothing was opened under is answered with nothing`() {
        assertNull(calendar.consultationById(ConsultationId(Ids.next())))
    }

    private fun dated(title: String, closesOn: LocalDate): ConsultationId {
        val consultation = consultations.openConsultation(draft(title), Ids.next().toString())

        consultations.recordTerm(
            consultation,
            ConsultationFact(
                opensOn = LocalDate.of(2026, 4, 9),
                closesOn = closesOn,
                daysAllowed = 21,
                submissionAddress = "konsultacje@ms.gov.pl",
                quote = QUOTE,
                charStart = 120,
                charEnd = 120 + QUOTE.length,
                statedIn = DocumentId(Ids.next()),
                statedBy = DocumentVersionId(Ids.next()),
            ),
        )

        return consultation
    }

    private fun draft(title: String): DraftId = drafts.insertDraft(
        DraftFromRegister(
            title = title,
            initiator = DraftInitiator.GOVERNMENT,
            term = 10,
            startedOn = LocalDate.of(2026, 4, 9),
        ),
    )

    private companion object {
        const val QUOTE = "proszę o zgłoszenie uwag w terminie 21 dni od dnia otrzymania niniejszego pisma"
    }
}
