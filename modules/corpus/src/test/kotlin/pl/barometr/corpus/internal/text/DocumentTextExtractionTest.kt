package pl.barometr.corpus.internal.text

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentTextExtracted
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.corpus.internal.BlobRepository
import pl.barometr.corpus.internal.DocumentRepository
import pl.barometr.corpus.internal.jooq.tables.references.BLOB
import pl.barometr.corpus.internal.jooq.tables.references.DOCUMENT
import pl.barometr.corpus.internal.jooq.tables.references.DOCUMENT_CHUNK
import pl.barometr.corpus.internal.jooq.tables.references.DOCUMENT_VERSION
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.shared.ContentHash
import pl.barometr.shared.Ids
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.SourceId
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.internal.FilesystemBlobStore
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The second derivation: an archived file becomes the text it says, and the chunks
 * that text is cited in.
 *
 * Run against the real files a ministry filed with a real bill — a Word document and
 * a PDF, both saved from RPL — because what is being proven is that Tika reads what
 * this source actually publishes. A synthetic document would prove that a synthetic
 * document parses.
 */
class DocumentTextExtractionTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private lateinit var blobs: FilesystemBlobStore
    private lateinit var events: RecordingEventPublisher
    private lateinit var meters: SimpleMeterRegistry
    private lateinit var documents: DocumentRepository
    private lateinit var blobIndex: BlobRepository
    private lateinit var extractor: DocumentTextExtractor

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(DOCUMENT).execute()
        dsl.deleteFrom(BLOB).execute()

        blobs = FilesystemBlobStore(blobRoot)
        events = RecordingEventPublisher()
        meters = SimpleMeterRegistry()
        documents = DocumentRepository(dsl, clock)
        blobIndex = BlobRepository(dsl, clock)
        extractor = DocumentTextExtractor(
            blobs = blobs,
            blobIndex = blobIndex,
            texts = DocumentTextRepository(dsl, clock),
            extraction = PlainTextExtraction(),
            chunker = TextChunker(),
            events = events,
            meters = meters,
            clock = clock,
        )
    }

    @Test
    fun `a Word document filed with a bill becomes text with its Polish intact`() {
        extractor.extractDocumentText(archived(fixture("uzasadnienie.docx")))

        val text = storedText()
        assertContains(text, "ubezpiecze")
        assertTrue(
            text.any { it in "ąćęłńóśźż" },
            "a justification written in Polish should still be in Polish after extraction",
        )
    }

    @Test
    fun `a PDF filed with a bill becomes text as well`() {
        extractor.extractDocumentText(archived(fixture("pismo-konsultacje.pdf")))

        assertTrue(storedText().isNotBlank())
        assertTrue(events.extracted.single().chunkCount > 0)
    }

    /**
     * The property everything downstream rests on, checked against a real document
     * rather than a constructed one: a chunk's range indexes the text that was
     * stored, so a citation resolves to the words it claims.
     */
    @Test
    fun `every stored chunk sits where the archive says it does`() {
        extractor.extractDocumentText(archived(fixture("uzasadnienie.docx")))

        val text = storedText()
        val chunks = dsl.selectFrom(DOCUMENT_CHUNK).orderBy(DOCUMENT_CHUNK.ORDINAL).fetch()

        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertEquals(
                chunk.content,
                text.substring(chunk.charStart!!, chunk.charEnd!!),
                "chunk ${chunk.ordinal} does not sit where it says it does",
            )
        }
    }

    @Test
    fun `the version records the text it was given, and when`() {
        extractor.extractDocumentText(archived(fixture("uzasadnienie.docx")))

        val version = assertNotNull(dsl.selectFrom(DOCUMENT_VERSION).fetchOne())
        val announced = events.extracted.single()

        assertEquals(announced.textHash, ContentHash.ofBytes(version.textHash!!))
        assertEquals(storedText().length, version.textLength)
        assertEquals(clock.instant(), version.extractedAt!!.toInstant())
        assertEquals(version.textLength, announced.textLength)
    }

    /**
     * Delivery is at-least-once, so this is the ordinary case rather than the edge
     * one. The claim is the database's — the update takes the version only if nothing
     * else has — so the second delivery writes no chunks and announces nothing.
     */
    @Test
    fun `a redelivered version is extracted once`() {
        val recorded = archived(fixture("uzasadnienie.docx"))

        extractor.extractDocumentText(recorded)
        val afterFirst = dsl.fetchCount(DOCUMENT_CHUNK)

        extractor.extractDocumentText(recorded)

        assertEquals(afterFirst, dsl.fetchCount(DOCUMENT_CHUNK))
        assertEquals(1, events.extracted.size)
    }

    /**
     * A payload no parser recognises leaves the version without text and says so in a
     * counter. Not an exception: it will not become readable on the fourth
     * redelivery, and the bytes are still in the archive for a parser that can.
     */
    @Test
    fun `a payload that says nothing is counted rather than recorded`() {
        extractor.extractDocumentText(archived(byteArrayOf(0x07, 0x00, 0x13, 0x37)))

        val version = assertNotNull(dsl.selectFrom(DOCUMENT_VERSION).fetchOne())
        assertNull(version.textHash)
        assertNull(version.extractedAt)
        assertEquals(0, dsl.fetchCount(DOCUMENT_CHUNK))
        assertEquals(1.0, counted("no-text"))
    }

    /**
     * A file that announces itself as a PDF and then is not one. Told apart from the
     * case above because only that one is what OCR would fix, and the size of that
     * pile is what decides whether OCR is worth building.
     */
    @Test
    fun `a corrupt file of a known format is counted as unreadable`() {
        extractor.extractDocumentText(archived("%PDF-1.5\nnot in fact a PDF at all".toByteArray()))

        assertNull(assertNotNull(dsl.selectFrom(DOCUMENT_VERSION).fetchOne()).textHash)
        assertEquals(1.0, counted("unreadable"))
        assertTrue(events.extracted.isEmpty())
    }

    /**
     * The raw bucket is the archive and is never touched; the text goes to the
     * derived bucket, which exists to be thrown away and recomputed. That is what
     * makes changing the chunk size or the parser a re-run rather than a re-crawl.
     */
    @Test
    fun `the text is derived, and the original stays exactly where it was`() {
        val payload = fixture("uzasadnienie.docx")
        val recorded = archived(payload)

        extractor.extractDocumentText(recorded)

        assertTrue(blobs.exists(BlobBucket.RAW, recorded.contentHash))
        assertTrue(blobs.exists(BlobBucket.DERIVED, events.extracted.single().textHash))
        assertEquals(
            payload.toList(),
            blobs.read(BlobBucket.RAW, recorded.contentHash)!!.use { it.readBytes() }.toList(),
        )
    }
    /**
     * The invariant belongs to the database rather than to the repository above it. A
     * version carrying a text hash and no length is not a state any reader knows how
     * to interpret, and it is exactly what a partial failure between two statements
     * would leave behind.
     */
    @Test
    fun `a half-extracted version is refused by the schema`() {
        val recorded = archived(fixture("uzasadnienie.docx"))

        assertFailsWith<DataAccessException> {
            dsl.update(DOCUMENT_VERSION)
                .set(DOCUMENT_VERSION.TEXT_HASH, recorded.contentHash.bytes)
                .where(DOCUMENT_VERSION.ID.eq(recorded.versionId.value))
                .execute()
        }
    }

    /**
     * Zero is not a length this system records. A version carrying an empty text blob
     * and no chunks reads as "extracted" to everything downstream, which is worse than
     * an honest null — so the payloads that yield nothing stay unextracted and counted
     * until OCR can do better.
     */
    @Test
    fun `an empty text is refused rather than recorded as extracted`() {
        val recorded = archived(fixture("uzasadnienie.docx"))

        assertFailsWith<DataAccessException> {
            dsl.update(DOCUMENT_VERSION)
                .set(DOCUMENT_VERSION.TEXT_HASH, recorded.contentHash.bytes)
                .set(DOCUMENT_VERSION.TEXT_LENGTH, 0)
                .set(DOCUMENT_VERSION.EXTRACTED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .where(DOCUMENT_VERSION.ID.eq(recorded.versionId.value))
                .execute()
        }
    }


    // ——— Fixtures ————————————————————————————————————————————————————————————

    /** Archives the payload the way ingestion does, and derives the version corpus would. */
    private fun archived(payload: ByteArray): DocumentVersionRecorded {
        val stored = blobs.store(BlobBucket.RAW, payload, ARCHIVED_MEDIA_TYPE)
        // The version references the blob row, exactly as the recorder writes it: a
        // version may not cite bytes the database has not been told about.
        blobIndex.recordStoredBlob(stored.contentHash, stored.byteSize, ARCHIVED_MEDIA_TYPE, BlobBucket.RAW)
        val documentId = documents.documentFor(SOURCE_ID, EXTERNAL_ID, KIND, title = null)
        val version = assertNotNull(
            documents.appendVersionIfNew(documentId, Ids.next(), stored.contentHash, publishedAt = null),
        )

        return DocumentVersionRecorded(
            documentId = documentId,
            versionId = version.id,
            sourceId = SOURCE_ID,
            connectorId = ConnectorId("rcl"),
            externalId = EXTERNAL_ID,
            kind = KIND,
            contentHash = stored.contentHash,
            versionNo = version.versionNo,
            occurredAt = clock.instant(),
        )
    }

    private fun storedText(): String =
        blobs.read(BlobBucket.DERIVED, events.extracted.single().textHash)!!
            .use { it.readBytes() }
            .toString(Charsets.UTF_8)

    private fun counted(reason: String): Double =
        meters.counter("corpus.text.unextracted", "reason", reason, "source", "rcl").count()

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/rcl/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }

    private class RecordingEventPublisher : ApplicationEventPublisher {
        private val published = mutableListOf<Any>()

        override fun publishEvent(event: ApplicationEvent) = publishEvent(event as Any)

        override fun publishEvent(event: Any) {
            published += event
        }

        val extracted: List<DocumentTextExtracted>
            get() = published.filterIsInstance<DocumentTextExtracted>()
    }

    private companion object {
        val SOURCE_ID = SourceId(Ids.next())
        val EXTERNAL_ID = ExternalId("projekt/ustawy/12409051/katalog/13196867/dokument/770752")
        val KIND = DocumentKind("rcl-filing")

        /** What the connector archives a filing under when nothing narrows it further. */
        const val ARCHIVED_MEDIA_TYPE = "application/octet-stream"
    }
}
