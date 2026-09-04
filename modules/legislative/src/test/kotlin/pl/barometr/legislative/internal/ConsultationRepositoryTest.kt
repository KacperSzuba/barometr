package pl.barometr.legislative.internal

import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.CATALOG_FOLDER
import pl.barometr.legislative.internal.jooq.tables.references.CONSULTATION
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Opening a consultation, finding it from a document that arrives months later, and
 * deciding which document gets to date it.
 *
 * The finding is the part with teeth. RPL addresses a filed document by the folder it
 * sits in, and the letter that opens a consultation is filed one folder below the
 * stage the consultation was opened on — so a join that only compared the two ids
 * would match nothing at all, in silence.
 */
class ConsultationRepositoryTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private val drafts = DraftRepository(dsl, clock)
    private val consultations = ConsultationRepository(dsl, clock)

    private var draftId = DraftId(Ids.next())

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(CATALOG_FOLDER).execute()
        dsl.deleteFrom(CONSULTATION).execute()
        dsl.deleteFrom(DRAFT).execute()

        draftId = drafts.insertDraft(
            DraftFromRegister(
                title = "Projekt ustawy o zmianie ustawy o kredycie konsumenckim",
                initiator = DraftInitiator.GOVERNMENT,
                term = 10,
                startedOn = LocalDate.of(2026, 4, 9),
            ),
        )
    }

    /**
     * A card is re-read every six hours for the life of the draft. The unique index is
     * what makes that a restatement rather than a second consultation, and it has to be
     * the index rather than a check in Kotlin: two deliveries of one card run at once.
     */
    @Test
    fun `a card read twice opens one consultation`() {
        val first = consultations.openConsultation(draftId, STAGE, CARD)
        val again = consultations.openConsultation(draftId, STAGE, CARD)

        assertEquals(first, again, "the same consultation, not a second one")
        assertEquals(1, dsl.fetchCount(CONSULTATION))
    }

    @Test
    fun `a consultation is opened with no dates and no evidence`() {
        consultations.openConsultation(draftId, STAGE, CARD)

        val row = assertNotNull(dsl.selectFrom(CONSULTATION).fetchOne())
        assertNull(row.closesOn, "a consultation nothing has dated yet")
        assertNull(row.statedBy)
        assertNull(row.quote)
    }

    @Test
    fun `a document filed in a folder of the stage belongs to its consultation`() {
        val consultation = consultations.openConsultation(draftId, STAGE, CARD)
        consultations.recordFolder(LETTERS_FOLDER, STAGE)

        assertEquals(consultation, consultations.consultationInCatalog(LETTERS_FOLDER))
    }

    /** Some ministries file the letter under the stage itself rather than a folder. */
    @Test
    fun `a document filed under the stage itself belongs to its consultation`() {
        val consultation = consultations.openConsultation(draftId, STAGE, CARD)

        assertEquals(consultation, consultations.consultationInCatalog(STAGE))
    }

    /**
     * The reason the folder is matched rather than the draft: a draft has eight stages
     * and files are filed under all of them, and a letter from "Opiniowanie" setting
     * the public consultation's deadline is exactly the quiet error this join exists to
     * prevent.
     */
    @Test
    fun `a document filed under another stage of the same draft belongs to no consultation`() {
        consultations.openConsultation(draftId, STAGE, CARD)
        consultations.recordFolder(OPINION_FOLDER, OTHER_STAGE)

        assertNull(consultations.consultationInCatalog(OPINION_FOLDER))
    }

    @Test
    fun `a folder recorded before its consultation was opened still matches`() {
        consultations.recordFolder(LETTERS_FOLDER, STAGE)
        val consultation = consultations.openConsultation(draftId, STAGE, CARD)

        assertEquals(
            consultation,
            consultations.consultationInCatalog(LETTERS_FOLDER),
            "the two halves arrive on concurrent listeners, in either order",
        )
    }

    @Test
    fun `a term read from a letter is recorded with the words it was read from`() {
        val consultation = consultations.openConsultation(draftId, STAGE, CARD)

        assertTrue(consultations.recordTerm(consultation, fact()))

        val row = assertNotNull(dsl.selectFrom(CONSULTATION).fetchOne())
        assertEquals(LocalDate.of(2026, 4, 30), row.closesOn)
        assertEquals(LocalDate.of(2026, 4, 9), row.openedOn)
        assertEquals(21, row.daysAllowed)
        assertEquals(QUOTE, row.quote)
        assertEquals(120, row.charStart)
        assertEquals(120 + QUOTE.length, row.charEnd)
    }

    /**
     * A dozen files are filed under one consultation stage — the draft, its
     * justification, an impact assessment, a table of comments — and the first of them
     * whose words set a term is the one this row believes.
     */
    @Test
    fun `a second document does not overwrite the term the first one stated`() {
        val consultation = consultations.openConsultation(draftId, STAGE, CARD)
        consultations.recordTerm(consultation, fact())

        val second = consultations.recordTerm(
            consultation,
            fact(closesOn = LocalDate.of(2027, 1, 1), document = DocumentId(Ids.next())),
        )

        assertFalse(second, "the impact assessment does not get to move the deadline")
        assertEquals(LocalDate.of(2026, 4, 30), dsl.selectFrom(CONSULTATION).fetchOne()?.closesOn)
    }

    /** A ministry replacing its own letter is a correction, and corrections apply. */
    @Test
    fun `a later version of the same document restates the term`() {
        val consultation = consultations.openConsultation(draftId, STAGE, CARD)
        val letter = DocumentId(Ids.next())
        consultations.recordTerm(consultation, fact(document = letter))

        val corrected = consultations.recordTerm(
            consultation,
            fact(closesOn = LocalDate.of(2026, 5, 15), document = letter),
        )

        assertTrue(corrected)
        assertEquals(LocalDate.of(2026, 5, 15), dsl.selectFrom(CONSULTATION).fetchOne()?.closesOn)
    }

    /**
     * `ck_consultation_term_has_evidence`, tried rather than trusted. A date with no
     * document behind it is a date this system invented, and the table is where that is
     * made impossible.
     */
    @Test
    fun `the database refuses a closing date with nothing behind it`() {
        val consultation = consultations.openConsultation(draftId, STAGE, CARD)

        assertFailsWith<DataAccessException> {
            dsl.update(CONSULTATION)
                .set(CONSULTATION.CLOSES_ON, LocalDate.of(2026, 4, 30))
                .where(CONSULTATION.ID.eq(consultation.value))
                .execute()
        }
    }

    /** `ck_consultation_period_counted`: a period with no day to count from is a number. */
    @Test
    fun `the database refuses a period with no day to count it from`() {
        val consultation = consultations.openConsultation(draftId, STAGE, CARD)

        assertFailsWith<DataAccessException> {
            dsl.update(CONSULTATION)
                .set(CONSULTATION.DAYS_ALLOWED, 21)
                .where(CONSULTATION.ID.eq(consultation.value))
                .execute()
        }
    }

    private fun fact(
        closesOn: LocalDate = LocalDate.of(2026, 4, 30),
        document: DocumentId = DocumentId(Ids.next()),
    ) = ConsultationFact(
        opensOn = LocalDate.of(2026, 4, 9),
        closesOn = closesOn,
        daysAllowed = 21,
        submissionAddress = "konsultacje@ms.gov.pl",
        quote = QUOTE,
        charStart = 120,
        charEnd = 120 + QUOTE.length,
        statedIn = document,
        statedBy = DocumentVersionId(Ids.next()),
    )

    private companion object {
        const val CARD = "projekt/ustawa/12409051"
        const val STAGE = "13196866"
        const val LETTERS_FOLDER = "13196868"
        const val OTHER_STAGE = "13196872"
        const val OPINION_FOLDER = "13196873"
        const val QUOTE = "proszę o zgłoszenie uwag w terminie 21 dni od dnia otrzymania niniejszego pisma"
    }
}
