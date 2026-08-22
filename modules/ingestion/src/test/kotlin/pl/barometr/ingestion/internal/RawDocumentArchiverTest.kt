package pl.barometr.ingestion.internal

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.ingestion.api.PayloadKind
import pl.barometr.ingestion.api.RawDocumentIngested
import pl.barometr.ingestion.api.RawPayload
import pl.barometr.ingestion.api.SchemaWarning
import pl.barometr.ingestion.api.SinkOutcome
import pl.barometr.ingestion.internal.jooq.tables.references.RAW_DOCUMENT
import pl.barometr.shared.ContentHash
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import pl.barometr.sources.api.SourceId
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.internal.FilesystemBlobStore
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ingestion contract in one test class: a connector hands over payloads and
 * everything that could go wrong — double storage, a duplicate row, a pipeline
 * re-run over content already processed — is prevented here rather than in the
 * connector.
 */
class RawDocumentArchiverTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private lateinit var events: RecordingEventPublisher
    private lateinit var sink: RunBoundRawDocumentSink
    // Not `lateinit`: Kotlin forbids it on value class types. Replaced per test.
    private var sourceId: SourceId = SourceId(Ids.next())

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(RAW_DOCUMENT).execute()

        // No source row to set up, and no `sources` migration on the classpath.
        // That is the payoff of dropping the cross-schema foreign key: this module
        // is testable entirely on its own schema.
        sourceId = SourceId(Ids.next())

        events = RecordingEventPublisher()
        // Composed the way Spring composes it: repository, then archiving policy,
        // then a run-scoped sink over the top. Nothing here needs a Spring context.
        sink = RawDocumentSinkFactory(archiverOver(blobRoot)).forRun(sourceId, runId = null)
    }

    @Test
    fun `new content is stored, recorded and announced`() {
        val payload = """{"druk":"123","tytul":"Ustawa o cenach energii"}""".toByteArray()

        val outcome = sink.archive(
            RawPayload(ExternalId("druk-123"), payload, PayloadKind.JSON, etag = "\"abc\""),
        )

        assertEquals(SinkOutcome.STORED, outcome)
        assertEquals(1, sink.documentsSeen)
        assertEquals(1, sink.documentsStored)

        val row = dsl.selectFrom(RAW_DOCUMENT).fetchSingle()
        assertEquals("druk-123", row[RAW_DOCUMENT.EXTERNAL_ID])
        assertEquals("json", row[RAW_DOCUMENT.PAYLOAD_KIND])
        assertEquals("\"abc\"", row[RAW_DOCUMENT.HTTP_ETAG])

        val expectedHash = ContentHash.of(payload)
        assertEquals(expectedHash.hex, ContentHash.ofBytes(row[RAW_DOCUMENT.CONTENT_HASH]!!).hex)
        // The blob key is derivable from the hash alone — no second source of truth.
        assertTrue(row[RAW_DOCUMENT.BLOB_KEY]!!.endsWith(expectedHash.hex))

        val event = events.ingested.single()
        assertEquals(expectedHash, event.contentHash)
        assertEquals(sourceId, event.sourceId)
    }

    /**
     * The single most important behaviour in the pipeline. A connector replaying a
     * range must not re-run extraction, embedding and alerting over content the
     * system already has — and the guarantee lives here, where a connector cannot
     * get it wrong.
     */
    @Test
    fun `identical content is recognised and publishes nothing`() {
        val payload = """{"druk":"123"}""".toByteArray()
        val document = { RawPayload(ExternalId("druk-123"), payload.copyOf(), PayloadKind.JSON) }

        assertEquals(SinkOutcome.STORED, sink.archive(document()))
        assertEquals(SinkOutcome.ALREADY_KNOWN, sink.archive(document()))
        assertEquals(SinkOutcome.ALREADY_KNOWN, sink.archive(document()))

        assertEquals(1, dsl.fetchCount(RAW_DOCUMENT), "one row for one piece of content")
        assertEquals(1, events.ingested.size, "the pipeline must run once")
        assertEquals(3, sink.documentsSeen)
        assertEquals(1, sink.documentsStored)
    }

    @Test
    fun `changed content under the same external id becomes a second row`() {
        val externalId = ExternalId("UD123")

        assertEquals(
            SinkOutcome.STORED,
            sink.archive(RawPayload(externalId, "wersja 1".toByteArray(), PayloadKind.HTML)),
        )
        assertEquals(
            SinkOutcome.STORED,
            sink.archive(RawPayload(externalId, "wersja 2".toByteArray(), PayloadKind.HTML)),
        )

        // Two versions of one document: the idempotency key includes the content
        // hash precisely so a revision is new rather than a duplicate.
        assertEquals(2, dsl.fetchCount(RAW_DOCUMENT))
        assertEquals(2, events.ingested.size)
    }

    @Test
    fun `the same bytes from two sources are stored once on disk`() {
        val payload = "identyczny PDF".toByteArray()
        val otherSourceId = SourceId(Ids.next())

        val factory = RawDocumentSinkFactory(archiverOver(blobRoot))

        factory.forRun(sourceId, null)
            .archive(RawPayload(ExternalId("druk-9"), payload.copyOf(), PayloadKind.PDF))
        factory.forRun(otherSourceId, null)
            .archive(RawPayload(ExternalId("UD-9"), payload.copyOf(), PayloadKind.PDF))

        // Two provenance rows — each source really did serve it — but one object.
        assertEquals(2, dsl.fetchCount(RAW_DOCUMENT))
        val objects = java.nio.file.Files.walk(blobRoot.resolve(BlobBucket.RAW.bucketName))
            .filter { java.nio.file.Files.isRegularFile(it) }
            .count()
        assertEquals(1, objects, "content addressing must collapse identical payloads")
    }

    @Test
    fun `schema warnings are collected for the run`() {
        sink.recordSchemaWarning(SchemaWarning("votings[].kind", SchemaWarning.Kind.UNKNOWN_FIELD, "saw 'ELECTRONIC_V2'"))
        sink.recordSchemaWarning(SchemaWarning("sitting.date", SchemaWarning.Kind.MISSING_FIELD))

        assertEquals(2, sink.schemaWarnings.size)
        assertTrue(sink.schemaWarnings.any { it.kind == SchemaWarning.Kind.UNKNOWN_FIELD })
    }

    private fun archiverOver(root: Path) = RawDocumentArchiver(
        blobs = FilesystemBlobStore(root),
        documents = RawDocumentRepository(dsl, clock),
        events = events,
        clock = clock,
    )

    private class RecordingEventPublisher : ApplicationEventPublisher {
        val published = mutableListOf<Any>()

        override fun publishEvent(event: ApplicationEvent) = publishEvent(event as Any)

        override fun publishEvent(event: Any) {
            published += event
        }

        val ingested: List<RawDocumentIngested> get() = published.filterIsInstance<RawDocumentIngested>()
    }

}
