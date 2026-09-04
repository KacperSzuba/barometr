package pl.barometr.legislative.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.barometr.corpus.api.ArchivedDocument
import pl.barometr.corpus.api.DocumentCatalog
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentTextExtracted
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.CATALOG_FOLDER
import pl.barometr.legislative.internal.jooq.tables.references.CONSULTATION
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.shared.Ids
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.internal.FilesystemBlobStore
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The last link of the chain: a PDF a ministry uploaded, archived, turned into text,
 * and finally read for the one date somebody will act on.
 *
 * What is asserted here is the join and the arithmetic — that a letter filed one folder
 * below the stage finds its consultation, that the term is moved off a day off before
 * it is stored, and that a document under another stage cannot date this one. What the
 * letters themselves are read to say is pinned in `ConsultationLetterReaderTest`.
 */
class ConsultationDeadlineRecorderTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private val consultations = ConsultationRepository(dsl, clock)
    private val documents = StubDocumentCatalog()

    private var draftId = DraftId(Ids.next())
    private lateinit var blobs: FilesystemBlobStore
    private lateinit var recorder: ConsultationDeadlineRecorder

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(CATALOG_FOLDER).execute()
        dsl.deleteFrom(CONSULTATION).execute()
        dsl.deleteFrom(DRAFT).execute()
        documents.clear()

        draftId = DraftRepository(dsl, clock).insertDraft(
            DraftFromRegister(
                title = "Projekt ustawy o zmianie ustawy o kredycie konsumenckim",
                initiator = DraftInitiator.GOVERNMENT,
                term = 10,
                startedOn = LocalDate.of(2026, 4, 9),
            ),
        )

        blobs = FilesystemBlobStore(blobRoot)
        recorder = ConsultationDeadlineRecorder(
            documents = documents,
            blobs = blobs,
            letters = ConsultationLetterReader(),
            consultations = consultations,
            meters = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `a letter filed in the consultation's folder dates it`() {
        consultations.openConsultation(draftId, STAGE)
        consultations.recordFolder(LETTERS_FOLDER, STAGE)

        recorder.recordDeadlineStatedInDocument(extracted(letter(writtenOn = "9 kwietnia 2026")))

        val row = assertNotNull(dsl.selectFrom(CONSULTATION).fetchOne())
        assertEquals(LocalDate.of(2026, 4, 9), row.openedOn, "the letter's own dateline")
        assertEquals(LocalDate.of(2026, 4, 30), row.closesOn)
        assertEquals(21, row.daysAllowed, "what the ministry actually wrote")
        assertEquals("konsultacje@ms.gov.pl", row.submissionAddress)
    }

    /**
     * `art. 57 § 4 k.p.a.`: twenty-one days from the tenth of April is the first of May,
     * which is a day off, followed by a weekend and the third of May. Printing the
     * ministry's arithmetic would tell somebody their comments were due three days
     * before they are.
     */
    @Test
    fun `a term ending on a day off closes on the next working day`() {
        consultations.openConsultation(draftId, STAGE)
        consultations.recordFolder(LETTERS_FOLDER, STAGE)

        recorder.recordDeadlineStatedInDocument(extracted(letter(writtenOn = "10 kwietnia 2026")))

        assertEquals(LocalDate.of(2026, 5, 4), dsl.selectFrom(CONSULTATION).fetchOne()?.closesOn)
    }

    @Test
    fun `the offsets recorded point at the sentence in the extracted text`() {
        consultations.openConsultation(draftId, STAGE)
        consultations.recordFolder(LETTERS_FOLDER, STAGE)
        val text = letter(writtenOn = "9 kwietnia 2026")

        recorder.recordDeadlineStatedInDocument(extracted(text))

        val row = assertNotNull(dsl.selectFrom(CONSULTATION).fetchOne())
        assertEquals(row.quote, text.substring(row.charStart!!, row.charEnd!!))
    }

    /**
     * A draft is out for comment in one stage and being opined on in another, and files
     * are filed under both. Only one of them is a deadline for the public.
     */
    @Test
    fun `a document filed under another stage dates nothing`() {
        consultations.openConsultation(draftId, STAGE)
        consultations.recordFolder(OPINION_FOLDER, OTHER_STAGE)

        recorder.recordDeadlineStatedInDocument(
            extracted(letter(writtenOn = "9 kwietnia 2026"), catalogId = OPINION_FOLDER),
        )

        assertNull(dsl.selectFrom(CONSULTATION).fetchOne()?.closesOn)
    }

    /**
     * Most files under a consultation stage are the draft, its justification and an
     * impact assessment. None of them asks anybody for anything, and a consultation
     * with no date is the honest answer until one does.
     */
    @Test
    fun `a document that states no term leaves the consultation undated`() {
        consultations.openConsultation(draftId, STAGE)
        consultations.recordFolder(LETTERS_FOLDER, STAGE)

        recorder.recordDeadlineStatedInDocument(
            extracted("Art. 13. Ustawa wchodzi w życie po upływie 14 dni od dnia ogłoszenia."),
        )

        val row = assertNotNull(dsl.selectFrom(CONSULTATION).fetchOne())
        assertNull(row.closesOn)
        assertNull(row.statedBy, "and nothing is cited for a date that was not read")
    }

    /**
     * A period is not a term until something says what to count it from. Recording it
     * would mean choosing a day, and the day this system would choose is the day it
     * happened to derive the text.
     */
    @Test
    fun `a period with no dateline leaves the consultation undated`() {
        consultations.openConsultation(draftId, STAGE)
        consultations.recordFolder(LETTERS_FOLDER, STAGE)

        recorder.recordDeadlineStatedInDocument(
            extracted("Uprzejmie proszę o zgłoszenie uwag w terminie 21 dni od dnia otrzymania pisma."),
        )

        assertNull(dsl.selectFrom(CONSULTATION).fetchOne()?.closesOn)
    }

    @Test
    fun `a document that is not RPL's is not looked at`() {
        consultations.openConsultation(draftId, STAGE)

        recorder.recordDeadlineStatedInDocument(
            extracted(letter(writtenOn = "9 kwietnia 2026"), externalId = ExternalId("term10/print/424")),
        )

        assertNull(dsl.selectFrom(CONSULTATION).fetchOne()?.closesOn)
    }

    private fun extracted(
        text: String,
        catalogId: String = LETTERS_FOLDER,
        externalId: ExternalId = ExternalId("projekt/ustawa/12409051/katalog/$catalogId/dokument/778141"),
    ): DocumentTextExtracted {
        val documentId = DocumentId(Ids.next())
        documents.record(documentId, externalId)
        val stored = blobs.store(BlobBucket.DERIVED, text.toByteArray(), "text/plain; charset=utf-8")

        return DocumentTextExtracted(
            documentId = documentId,
            versionId = DocumentVersionId(Ids.next()),
            textHash = stored.contentHash,
            textLength = text.length,
            chunkCount = 1,
            occurredAt = clock.instant(),
        )
    }

    private fun letter(writtenOn: String) = """
        Warszawa, dnia $writtenOn r.

        MINISTER SPRAWIEDLIWOŚCI

        Uprzejmie przekazuję w załączeniu projekt ustawy z prośbą o zgłoszenie uwag
        w terminie 21 dni od dnia otrzymania niniejszego pisma.
        Uwagi proszę przesyłać na adres konsultacje@ms.gov.pl w wersji edytowalnej.
    """.trimIndent()

    /**
     * Stands in for corpus, which this context asks one question of: what address was
     * this document archived under. Hand-written rather than mocked, so a change to the
     * port is a compile error here rather than a stub that keeps agreeing.
     */
    private class StubDocumentCatalog : DocumentCatalog {
        private val archived = mutableMapOf<DocumentId, ArchivedDocument>()

        fun record(id: DocumentId, externalId: ExternalId) {
            archived[id] = ArchivedDocument(id, externalId, DocumentKind.UNKNOWN, title = null, publishedAt = null)
        }

        fun clear() = archived.clear()

        override fun documentById(id: DocumentId) = archived[id]

        override fun countByKind() = emptyMap<DocumentKind, Int>()
    }

    private companion object {
        const val STAGE = "13196866"
        const val LETTERS_FOLDER = "13196868"
        const val OTHER_STAGE = "13196872"
        const val OPINION_FOLDER = "13196873"
    }
}
