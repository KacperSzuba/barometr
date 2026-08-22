package pl.barometr.corpus.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.corpus.internal.jooq.tables.references.BLOB
import pl.barometr.corpus.internal.jooq.tables.references.DOCUMENT
import pl.barometr.corpus.internal.jooq.tables.references.DOCUMENT_VERSION
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.ingestion.api.PayloadKind
import pl.barometr.ingestion.api.RawDocumentIngested
import pl.barometr.shared.ContentHash
import pl.barometr.shared.Ids
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.SourceDefinition
import pl.barometr.sources.api.SourceId
import pl.barometr.sources.api.SourceRegistry
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.internal.FilesystemBlobStore
import pl.barometr.storage.internal.StorageProperties
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.net.URI
import java.nio.file.Path
import java.util.Collections
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The derivation contract in one class: an archived payload becomes a document and a
 * version of it, exactly once, however many times the event is delivered.
 *
 * Composed by hand the way Spring composes it — repositories, blob store, readers —
 * because everything here is worth testing without a context. The database is real
 * though: version numbering and chaining happen inside a statement, and testing them
 * against anything but Postgres would be testing the fake.
 */
class ArchivedDocumentRecorderTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()

    private lateinit var blobs: FilesystemBlobStore
    private lateinit var events: RecordingEventPublisher
    private lateinit var meters: SimpleMeterRegistry
    private lateinit var documents: DocumentRepository
    private lateinit var blobIndex: BlobRepository
    private lateinit var recorder: ArchivedDocumentRecorder

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(DOCUMENT).execute()
        dsl.deleteFrom(BLOB).execute()

        blobs = FilesystemBlobStore(StorageProperties(blobRoot))
        events = RecordingEventPublisher()
        meters = SimpleMeterRegistry()
        documents = DocumentRepository(dsl, clock)
        blobIndex = BlobRepository(dsl, clock)
        recorder = recorderFor(SejmArchivedDocumentReader(json))
    }

    @Test
    fun `an archived payload becomes the first version of a new document`() {
        recorder.recordDocumentVersion(archive(PRINT, print(title = "Ustawa o cenach energii")))

        val document = assertNotNull(dsl.selectFrom(DOCUMENT).fetchOne())
        assertEquals(PRINT.value, document.externalId)
        assertEquals("print", document.kind)
        assertEquals("Ustawa o cenach energii", document.title)

        val version = assertNotNull(dsl.selectFrom(DOCUMENT_VERSION).fetchOne())
        assertEquals(1, version.versionNo)
        assertNull(version.previousVersionId, "the first version has nothing before it")

        val recorded = events.recorded.single()
        assertEquals(1, recorded.versionNo)
        assertEquals(DocumentKind("print"), recorded.kind)
        assertEquals(ConnectorId("sejm"), recorded.connectorId)
    }

    /**
     * The version chain is what the whole thing is for: an amended print is a new
     * version of the same document, pointing at the one it replaced, rather than a
     * second document nobody can line up against the first.
     */
    @Test
    fun `changed content is chained onto the version before it`() {
        recorder.recordDocumentVersion(archive(PRINT, print(title = "Ustawa o cenach energii")))
        clock.advanceBy(Duration.ofHours(1))
        recorder.recordDocumentVersion(archive(PRINT, print(title = "Ustawa o cenach energii (poprawiona)")))

        val chain = dsl.selectFrom(DOCUMENT_VERSION)
            .orderBy(DOCUMENT_VERSION.VERSION_NO)
            .fetch()

        assertEquals(listOf(1, 2), chain.map { it.versionNo })
        assertEquals(chain[0].id, chain[1].previousVersionId)
        assertEquals(1, dsl.fetchCount(DOCUMENT), "an amended print is not a second document")
        // The document takes the newer title, which is what a reader expects to see.
        assertEquals("Ustawa o cenach energii (poprawiona)", dsl.selectFrom(DOCUMENT).fetchOne()?.title)
    }

    /**
     * Redelivery is not exceptional: Spring Modulith republishes anything a listener
     * did not finish, and a connector replay re-archives whole years. Both must cost
     * nothing.
     */
    @Test
    fun `content the archive already holds records nothing and announces nothing`() {
        val ingested = archive(PRINT, print(title = "Ustawa o cenach energii"))

        recorder.recordDocumentVersion(ingested)
        recorder.recordDocumentVersion(ingested)

        assertEquals(1, dsl.fetchCount(DOCUMENT_VERSION))
        assertEquals(1, events.recorded.size, "a redelivery must not announce a version again")
    }

    @Test
    fun `a source no reader can read is counted rather than guessed at`() {
        val withoutReaders = recorderFor()

        withoutReaders.recordDocumentVersion(archive(PRINT, print(title = "Ustawa o cenach energii")))

        assertEquals(0, dsl.fetchCount(DOCUMENT))
        assertEquals(1.0, counted("no-reader"))
    }

    /**
     * The bytes are the archive's whole point; a version pointing at bytes nobody
     * stored would be provenance that cannot be checked.
     */
    @Test
    fun `a payload the object store does not hold is counted rather than invented`() {
        val missing = RawDocumentIngested(
            rawDocumentId = Ids.next(),
            sourceId = SEJM_SOURCE.id,
            externalId = PRINT,
            contentHash = ContentHash.of("never stored".toByteArray()),
            kind = PayloadKind.JSON,
            occurredAt = clock.instant(),
        )

        recorder.recordDocumentVersion(missing)

        assertEquals(0, dsl.fetchCount(DOCUMENT))
        assertEquals(1.0, counted("payload-missing"))
    }

    /**
     * Numbering and chaining are computed inside the insert, so two deliveries racing
     * on one document cannot both read "the latest is 1" and both write a 2. The
     * loser is rejected by the unique index rather than chained onto the same
     * predecessor — a rejected version is redelivered, a forked chain is forever.
     */
    @Test
    fun `versions racing on one document cannot fork the chain`() {
        val documentId = documents.documentFor(SEJM_SOURCE.id, PRINT, DocumentKind("print"), title = null)
        val contents = listOf("first".toByteArray(), "second".toByteArray())
        contents.forEach { content ->
            val stored = blobs.store(BlobBucket.RAW, content, "application/json")
            blobIndex.recordStoredBlob(stored.contentHash, stored.byteSize, stored.mediaType, BlobBucket.RAW)
        }

        val refused = Collections.synchronizedList(mutableListOf<Throwable>())
        contents.map { content ->
            Thread.ofVirtual().start {
                try {
                    documents.appendVersionIfNew(documentId, Ids.next(), ContentHash.of(content), publishedAt = null)
                } catch (rejected: DataAccessException) {
                    refused += rejected
                }
            }
        }.forEach { it.join() }

        // At most one may be turned away, and only by the index that keeps the chain
        // straight — anything else means the insert is failing for a reason this test
        // is not describing.
        assertTrue(refused.size <= 1, "both versions were refused: ${refused.map { it.message }}")

        // Whatever the two threads managed, what is on disk is a chain: distinct
        // numbers, and every version but the first pointing at its predecessor.
        val chain = dsl.selectFrom(DOCUMENT_VERSION).orderBy(DOCUMENT_VERSION.VERSION_NO).fetch()
        assertTrue(chain.isNotEmpty(), "neither version was written")
        assertEquals(chain.map { it.versionNo }.distinct().size, chain.size, "two versions cannot share a number")
        assertNull(chain.first().previousVersionId)
        chain.drop(1).forEachIndexed { index, version ->
            assertEquals(chain[index].id, version.previousVersionId, "version ${version.versionNo} left the chain")
        }
    }

    // ——— Harness ————————————————————————————————————————————————————————————

    private fun recorderFor(vararg readers: ArchivedDocumentReader) = ArchivedDocumentRecorder(
        sources = FakeSourceRegistry(SEJM_SOURCE),
        readers = ArchivedDocumentReaders(readers.toList()),
        blobs = blobs,
        blobIndex = blobIndex,
        documents = documents,
        events = events,
        meters = meters,
        clock = clock,
    )

    /** Stores the payload the way the archiver does, and reports it the way it does. */
    private fun archive(externalId: ExternalId, payload: ByteArray): RawDocumentIngested {
        val stored = blobs.store(BlobBucket.RAW, payload, "application/json")

        return RawDocumentIngested(
            rawDocumentId = Ids.next(),
            sourceId = SEJM_SOURCE.id,
            externalId = externalId,
            contentHash = stored.contentHash,
            kind = PayloadKind.JSON,
            occurredAt = clock.instant(),
        )
    }

    private fun print(title: String): ByteArray =
        """{"number":"3005","title":"$title","documentDate":"2026-08-12"}""".toByteArray()

    private fun counted(reason: String): Double =
        meters.counter("corpus.documents.underived", "reason", reason, "source", "sejm").count()

    private class FakeSourceRegistry(private val source: SourceDefinition) : SourceRegistry {
        override fun enabled() = listOf(source)

        override fun byConnector(connectorId: ConnectorId) =
            source.takeIf { connectorId == source.connectorId }

        override fun enabledById(id: SourceId) = source.takeIf { id == source.id }

        override fun byId(id: SourceId) = source.takeIf { id == source.id }
    }

    private class RecordingEventPublisher : ApplicationEventPublisher {
        val published = mutableListOf<Any>()

        override fun publishEvent(event: ApplicationEvent) = publishEvent(event as Any)

        override fun publishEvent(event: Any) {
            published += event
        }

        val recorded: List<DocumentVersionRecorded>
            get() = published.filterIsInstance<DocumentVersionRecorded>()
    }

    private companion object {
        val PRINT = ExternalId("term10/print/3005")

        val SEJM_SOURCE = SourceDefinition(
            id = SourceId(Ids.next()),
            connectorId = ConnectorId("sejm"),
            name = "API Sejmu",
            baseUrl = URI.create("https://api.sejm.gov.pl"),
            refreshInterval = Duration.ofMinutes(15),
            expectedMinRecordsPerRun = null,
        )
    }
}
