package pl.barometr.corpus.internal.diff

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import pl.barometr.corpus.api.ChangeKind
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.corpus.api.DocumentVersionsCompared
import pl.barometr.corpus.internal.BlobRepository
import pl.barometr.corpus.internal.DocumentRepository
import pl.barometr.corpus.internal.jooq.tables.references.BLOB
import pl.barometr.corpus.internal.jooq.tables.references.DOCUMENT
import pl.barometr.corpus.internal.jooq.tables.references.UNIT_CHANGE
import pl.barometr.corpus.internal.structure.EditorialUnitReader
import pl.barometr.corpus.internal.text.DocumentTextRepository
import pl.barometr.corpus.internal.text.TextChunker
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.shared.Ids
import pl.barometr.sources.api.SourceId
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.internal.FilesystemBlobStore
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The comparison as it is actually recorded: two versions in the archive, their text in
 * the derived bucket, and a row per change that anything downstream can cite.
 *
 * On a real Postgres because the guarantees being checked are the database's — the
 * unique index that makes a recomputation free, and the `CHECK` that refuses a change
 * whose kind and sides disagree.
 */
class VersionComparisonTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private lateinit var blobs: FilesystemBlobStore
    private lateinit var events: RecordingEventPublisher
    private lateinit var documents: DocumentRepository
    private lateinit var blobIndex: BlobRepository
    private lateinit var texts: DocumentTextRepository
    private lateinit var diffs: VersionDiffRepository
    private lateinit var comparison: VersionComparison
    private lateinit var changes: ComparedChanges

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(DOCUMENT).execute()
        dsl.deleteFrom(BLOB).execute()

        blobs = FilesystemBlobStore(blobRoot)
        events = RecordingEventPublisher()
        documents = DocumentRepository(dsl, clock)
        blobIndex = BlobRepository(dsl, clock)
        texts = DocumentTextRepository(dsl, clock)
        diffs = VersionDiffRepository(dsl, json)
        comparison = VersionComparison(
            blobs = blobs,
            reader = EditorialUnitReader(),
            alignment = UnitAlignment(DiffProperties()),
            words = WordLevelChanges(DiffProperties()),
            diffs = diffs,
            events = events,
            meters = SimpleMeterRegistry(),
            clock = clock,
        )
        changes = ComparedChanges(diffs, blobs)
    }

    @Test
    fun `a comparison is recorded with a row for every change, and announced once`() {
        val pair = archived(FIRST_DRAFT, SECOND_DRAFT)

        val diff = assertNotNull(comparison.compareVersions(pair))

        assertEquals(diff.changeCount, dsl.fetchCount(UNIT_CHANGE))
        assertEquals(1, diff.unitsAdded, "the new article")
        // The final article, the second paragraph of the one before it, and that
        // article's own lead-in line: all three kept their words and changed their
        // number, which is a renumbering rather than three deletions and three
        // insertions.
        assertEquals(3, diff.unitsMoved, "what the insertion renumbered")
        assertEquals(1, diff.unitsModified, "the term that went from 14 days to 30")
        assertEquals(0, diff.unitsRemoved, "nothing was deleted")
        assertEquals(2, diff.substantiveChanges, "the new article and the changed term; a renumbering is neither")

        val announced = events.compared.single()
        assertEquals(diff.id, announced.diffId)
        assertEquals(diff.substantiveChanges, announced.substantiveChanges)
    }

    @Test
    fun `comparing the same pair again records nothing and announces nothing`() {
        val pair = archived(FIRST_DRAFT, SECOND_DRAFT)
        val first = assertNotNull(comparison.compareVersions(pair))

        assertNull(comparison.compareVersions(pair), "the pair was already compared")
        assertEquals(first.changeCount, dsl.fetchCount(UNIT_CHANGE), "no change was written twice")
        assertEquals(1, events.compared.size)
    }

    @Test
    fun `every stored range quotes the words it claims`() {
        val pair = archived(FIRST_DRAFT, SECOND_DRAFT)
        val diff = assertNotNull(comparison.compareVersions(pair))

        val page = changes.changesOf(
            documentId = diff.documentId,
            from = null,
            to = null,
            substantiveOnly = false,
            afterOrdinal = 0,
            limit = 50,
        )

        assertEquals(diff.id, page.diff.id)
        page.changes.forEach { quoted ->
            quoted.before?.let { assertTrue(FIRST_DRAFT.contains(it), "'$it' is not in the older version") }
            quoted.after?.let { assertTrue(SECOND_DRAFT.contains(it), "'$it' is not in the newer version") }
        }
        assertTrue(page.changes.any { it.change.kind == ChangeKind.MOVED && it.change.renumbered })
    }

    /**
     * The word-level detail survives the trip through the `jsonb` column, and still
     * quotes the two versions rather than a copy of them.
     */
    @Test
    fun `the words that changed are read back pointing at both versions`() {
        val pair = archived(FIRST_DRAFT, SECOND_DRAFT)
        val diff = assertNotNull(comparison.compareVersions(pair))

        val modified = changes.changesOf(diff.documentId, null, null, false, 0, 50)
            .changes
            .single { it.change.kind == ChangeKind.MODIFIED }
        val word = modified.change.words.single()

        assertEquals("14", FIRST_DRAFT.substring(word.fromCharStart!!, word.fromCharEnd!!))
        assertEquals("30", SECOND_DRAFT.substring(word.toCharStart!!, word.toCharEnd!!))
        assertEquals(false, modified.change.wordsTruncated)
    }

    @Test
    fun `only the substantive changes are returned when that is what was asked for`() {
        val pair = archived(FIRST_DRAFT, SECOND_DRAFT)
        val diff = assertNotNull(comparison.compareVersions(pair))

        val substantive = changes.changesOf(diff.documentId, null, null, true, 0, 50).changes

        assertTrue(substantive.isNotEmpty())
        assertTrue(substantive.none { it.change.kind == ChangeKind.MOVED }, "a renumbering is not substantive")
    }

    @Test
    fun `a pair is waiting for comparison until it has one`() {
        val pair = archived(FIRST_DRAFT, SECOND_DRAFT)

        assertEquals(
            listOf(pair),
            diffs.pairsAwaitingComparison(VersionComparison.READER_VERSION, after = null, limit = 10),
        )

        comparison.compareVersions(pair)

        assertEquals(
            emptyList(),
            diffs.pairsAwaitingComparison(VersionComparison.READER_VERSION, after = null, limit = 10),
        )
    }

    @Test
    fun `a version whose predecessor has no text is not half of a pair`() {
        val documentId = documents.documentFor(SOURCE_ID, EXTERNAL_ID, KIND, title = null)
        val first = assertNotNull(documents.appendVersionIfNew(documentId, Ids.next(), archivedBytes(FIRST_DRAFT), null))
        val second = assertNotNull(documents.appendVersionIfNew(documentId, Ids.next(), archivedBytes(SECOND_DRAFT), null))
        // Only the newer one gets its text: the older is a scan with no text layer.
        extract(second.id, SECOND_DRAFT)

        assertEquals(emptyList(), diffs.pairsAround(second.id))
        assertNull(diffs.pairOf(first.id, second.id))
    }

    /**
     * The kind of a change and the sides it carries are one fact, and the database
     * holds it. A removal that names a position in the newer version is not a change
     * any reader knows how to render.
     */
    @Test
    fun `a removal that claims a place in the newer version is refused by the schema`() {
        val pair = archived(FIRST_DRAFT, SECOND_DRAFT)
        val diff = assertNotNull(comparison.compareVersions(pair))

        assertFailsWith<DataAccessException> {
            dsl.insertInto(UNIT_CHANGE)
                .set(UNIT_CHANGE.DIFF_ID, diff.id.value)
                .set(UNIT_CHANGE.ORDINAL, 9_000)
                .set(UNIT_CHANGE.KIND, ChangeKind.REMOVED.wireName)
                .set(UNIT_CHANGE.UNIT_KIND, "art")
                .set(UNIT_CHANGE.SUBSTANTIVE, true)
                .set(UNIT_CHANGE.FROM_PATH, "art-5")
                .set(UNIT_CHANGE.FROM_CHAR_START, 0)
                .set(UNIT_CHANGE.FROM_CHAR_END, 10)
                .set(UNIT_CHANGE.TO_PATH, "art-5")
                .set(UNIT_CHANGE.TO_CHAR_START, 0)
                .set(UNIT_CHANGE.TO_CHAR_END, 10)
                .execute()
        }
    }

    // ——— Fixtures ————————————————————————————————————————————————————————————

    /** Two versions of one document, both archived and both with their text extracted. */
    private fun archived(before: String, after: String): ComparablePair {
        val documentId = documents.documentFor(SOURCE_ID, EXTERNAL_ID, KIND, title = null)

        val first = assertNotNull(documents.appendVersionIfNew(documentId, Ids.next(), archivedBytes(before), null))
        extract(first.id, before)
        val second = assertNotNull(documents.appendVersionIfNew(documentId, Ids.next(), archivedBytes(after), null))
        extract(second.id, after)

        return assertNotNull(diffs.pairOf(first.id, second.id))
    }

    /** The payload as ingestion stored it — bytes nobody reads here, but a version must cite some. */
    private fun archivedBytes(text: String) =
        blobs.store(BlobBucket.RAW, text.toByteArray(), "text/plain")
            .also { blobIndex.recordStoredBlob(it.contentHash, it.byteSize, "text/plain", BlobBucket.RAW) }
            .contentHash

    /** The extracted text, stored where the comparison reads it from. */
    private fun extract(versionId: DocumentVersionId, text: String) {
        val stored = blobs.store(BlobBucket.DERIVED, text.toByteArray(Charsets.UTF_8), "text/plain")
        blobIndex.recordStoredBlob(stored.contentHash, stored.byteSize, "text/plain", BlobBucket.DERIVED)
        texts.recordExtractedText(versionId, stored.contentHash, text.length, TextChunker().chunk(text))
    }

    private class RecordingEventPublisher : ApplicationEventPublisher {
        private val published = mutableListOf<Any>()

        override fun publishEvent(event: ApplicationEvent) = publishEvent(event as Any)

        override fun publishEvent(event: Any) {
            published += event
        }

        val compared: List<DocumentVersionsCompared>
            get() = published.filterIsInstance<DocumentVersionsCompared>()
    }

    private companion object {
        val SOURCE_ID = SourceId(Ids.next())
        val EXTERNAL_ID = ExternalId("projekt/ustawy/12409051/katalog/13196867/dokument/770752")
        val KIND = DocumentKind("rcl-filed-document")

        val FIRST_DRAFT = """
            USTAWA
            z dnia 3 marca 2026 r.
            o zmianie ustawy o odpadach

            Art. 5. Przedsiębiorca prowadzi ewidencję odpadów w postaci elektronicznej.
            Art. 6. 1. Wniosek o wpis składa się w terminie 14 dni od dnia rozpoczęcia działalności.
            2. Do wniosku dołącza się kopię decyzji o środowiskowych uwarunkowaniach.
            Art. 7. Ustawa wchodzi w życie po upływie 14 dni od dnia ogłoszenia.
        """.trimIndent()

        val SECOND_DRAFT = """
            USTAWA
            z dnia 3 marca 2026 r.
            o zmianie ustawy o odpadach

            Art. 5. Przedsiębiorca prowadzi ewidencję odpadów w postaci elektronicznej.
            Art. 6. Minister właściwy do spraw klimatu prowadzi rejestr przedsiębiorców.
            Art. 7. 1. Wniosek o wpis składa się w terminie 30 dni od dnia rozpoczęcia działalności.
            2. Do wniosku dołącza się kopię decyzji o środowiskowych uwarunkowaniach.
            Art. 8. Ustawa wchodzi w życie po upływie 14 dni od dnia ogłoszenia.
        """.trimIndent()
    }
}
