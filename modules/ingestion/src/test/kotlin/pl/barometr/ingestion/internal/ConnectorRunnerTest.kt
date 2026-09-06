package pl.barometr.ingestion.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import pl.barometr.ingestion.api.BackfillConnector
import pl.barometr.ingestion.api.BackfillPartition
import pl.barometr.ingestion.api.Connector
import pl.barometr.ingestion.api.Cursor
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.ingestion.api.FetchResult
import pl.barometr.ingestion.api.IncrementalConnector
import pl.barometr.ingestion.api.PayloadKind
import pl.barometr.ingestion.api.RawDocumentSink
import pl.barometr.ingestion.api.RawPayload
import pl.barometr.ingestion.api.SchemaWarning
import pl.barometr.ingestion.internal.jooq.tables.references.RAW_DOCUMENT
import pl.barometr.shared.Ids
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.IngestionMode
import pl.barometr.sources.api.RunOutcome
import pl.barometr.sources.api.SourceDefinition
import pl.barometr.sources.api.SourceId
import pl.barometr.storage.internal.FilesystemBlobStore
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One run of one connector, and the promises the runner makes about it: the position
 * advances only when the read succeeded, a failure is recorded rather than swallowed,
 * the counts come from the sink, and a mode a connector cannot serve is a refusal
 * rather than a cast that throws.
 *
 * Composed the way Spring composes it — the real registry, the real sink over a real
 * archive — with fakes only where the collaborator is another context's storage. The
 * cursor rule in particular is the one that loses documents silently when it is wrong.
 */
class ConnectorRunnerTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val cursors = InMemoryCursors()
    private val runs = RecordingRuns()

    private val source = SourceDefinition(
        id = SourceId(Ids.next()),
        connectorId = ConnectorId("sejm"),
        name = "API Sejmu",
        baseUrl = URI.create("https://api.sejm.gov.pl"),
        refreshInterval = Duration.ofMinutes(15),
        expectedMinRecordsPerRun = null,
    )

    @BeforeEach
    fun clearTheArchive() {
        dsl.deleteFrom(RAW_DOCUMENT).execute()
    }

    @Test
    fun `a successful read commits the position the connector returned`() {
        val connector = FakeIncremental(nextCursor = mapOf("changedThrough" to "2026-08-21"))

        runnerOver(connector).readSourceOnce(source, IngestionMode.INCREMENTAL)

        assertEquals(
            mapOf("changedThrough" to "2026-08-21"),
            cursors.load(source.id, IngestionMode.INCREMENTAL),
        )
        assertEquals(RunOutcome.SUCCEEDED, runs.onlyFinished.outcome)
    }

    /**
     * The rule this class exists to protect. A position advanced past documents that
     * were never stored skips them for good — nothing re-reads a range the cursor says
     * is behind us.
     */
    @Test
    fun `a read that fails leaves the previous position untouched`() {
        cursors.save(source.id, IngestionMode.INCREMENTAL, mapOf("changedThrough" to "2026-08-01"))
        val connector = FakeIncremental(failWith = IllegalStateException("the source went away"))

        assertFailsWith<IllegalStateException> {
            runnerOver(connector).readSourceOnce(source, IngestionMode.INCREMENTAL)
        }

        assertEquals(
            mapOf("changedThrough" to "2026-08-01"),
            cursors.load(source.id, IngestionMode.INCREMENTAL),
            "the position may only move past documents that were actually archived",
        )
    }

    /**
     * Rethrown so the queue applies its backoff: a broken source that looks like a
     * series of quiet successes is the failure this system is least able to notice.
     */
    @Test
    fun `a failed run is recorded as failed, with whatever got through before it`() {
        val connector = FakeIncremental(
            archiving = listOf("druk-1", "druk-2"),
            failWith = IllegalStateException("connection reset"),
        )

        assertFailsWith<IllegalStateException> {
            runnerOver(connector).readSourceOnce(source, IngestionMode.INCREMENTAL)
        }

        val finished = runs.onlyFinished
        assertEquals(RunOutcome.FAILED, finished.outcome)
        assertEquals("connection reset", finished.report.failureReason)
        assertEquals(2, finished.report.documentsStored, "what was archived before the failure still counts")
        assertEquals(1, finished.report.errors)
    }

    /**
     * The sink sees every payload and knows which content the archive already held; a
     * connector counting alongside it was a second set of numbers that could disagree.
     */
    @Test
    fun `the report counts what the sink saw and stored`() {
        val connector = FakeIncremental(archiving = listOf("druk-1", "druk-1", "druk-2"))

        runnerOver(connector).readSourceOnce(source, IngestionMode.INCREMENTAL)

        val report = runs.onlyFinished.report
        assertEquals(3, report.documentsSeen)
        assertEquals(2, report.documentsStored, "the same content twice is stored once")
    }

    @Test
    fun `a schema warning the connector recorded travels into the run's report`() {
        val connector = FakeIncremental(
            warning = SchemaWarning("/sejm/term10/prints", SchemaWarning.Kind.MISSING_FIELD, "no changeDate"),
        )

        runnerOver(connector).readSourceOnce(source, IngestionMode.INCREMENTAL)

        assertEquals(
            listOf("MISSING_FIELD:/sejm/term10/prints (no changeDate)"),
            runs.onlyFinished.report.schemaWarnings,
        )
    }

    /**
     * What a connector supports is the set of interfaces it implements, so asking for a
     * mode it does not serve is a typed refusal and not a `ClassCastException` raised
     * after the run row was opened.
     */
    @Test
    fun `a mode the connector does not serve fails the run instead of casting`() {
        val connector = FakeIncremental()

        assertFailsWith<ModeNotSupportedException> {
            runnerOver(connector).readSourceOnce(source, IngestionMode.BACKFILL, partition = "term10")
        }

        assertEquals(RunOutcome.FAILED, runs.onlyFinished.outcome, "the run was opened, so it must be closed")
    }

    @Test
    fun `a source naming a connector this deployment does not carry is refused before a run is opened`() {
        val runner = runnerOver(FakeIncremental())
        val unknown = source.copy(connectorId = ConnectorId("nieznany"))

        assertFailsWith<UnknownConnectorException> { runner.readSourceOnce(unknown, IngestionMode.INCREMENTAL) }

        assertTrue(runs.started.isEmpty(), "nothing to record: the run never began")
    }

    /**
     * A backfill's position belongs to its partition. Saved anywhere else, resuming term
     * ten would resume from where term nine left off.
     */
    @Test
    fun `a backfill commits its position under the partition it read`() {
        val connector = FakeBackfill(nextCursor = mapOf("offset" to "500"))

        runnerOver(connector).readSourceOnce(source, IngestionMode.BACKFILL, partition = "term10")

        assertEquals(mapOf("offset" to "500"), cursors.load(source.id, IngestionMode.BACKFILL, "term10"))
        assertNull(cursors.load(source.id, IngestionMode.BACKFILL), "the unpartitioned position is a different one")
        assertEquals(
            mapOf("term10" to mapOf("offset" to "500")),
            cursors.partitions(source.id, IngestionMode.BACKFILL),
        )
    }

    @Test
    fun `a connector that reports no new position leaves the old one alone`() {
        cursors.save(source.id, IngestionMode.INCREMENTAL, mapOf("changedThrough" to "2026-08-01"))

        runnerOver(FakeIncremental(nextCursor = null)).readSourceOnce(source, IngestionMode.INCREMENTAL)

        assertEquals(mapOf("changedThrough" to "2026-08-01"), cursors.load(source.id, IngestionMode.INCREMENTAL))
        assertEquals(RunOutcome.SUCCEEDED, runs.onlyFinished.outcome)
    }

    // ——— Harness ————————————————————————————————————————————————————————————

    private fun runnerOver(connector: Connector) = ConnectorRunner(
        connectors = ConnectorRegistry(listOf(connector)),
        sinkFactory = RawDocumentSinkFactory(
            RawDocumentArchiver(
                blobs = FilesystemBlobStore(blobRoot),
                documents = RawDocumentRepository(dsl, clock),
                events = SilentEvents,
                clock = clock,
            ),
        ),
        cursors = cursors,
        runs = runs,
        health = SourceHealthMonitor(runs, SimpleMeterRegistry()),
    )

    /** A connector that archives what it was told to, then does what it was told to. */
    private class FakeIncremental(
        private val archiving: List<String> = emptyList(),
        private val nextCursor: Map<String, String>? = null,
        private val warning: SchemaWarning? = null,
        private val failWith: Exception? = null,
    ) : IncrementalConnector {

        override val id = ConnectorId("sejm")

        override fun readChangesSince(cursor: Cursor?, sink: RawDocumentSink): FetchResult {
            archiving.forEach { sink.archive(payloadFor(it)) }
            warning?.let(sink::recordSchemaWarning)
            failWith?.let { throw it }

            return FetchResult(nextCursor = nextCursor?.let { Cursor(IngestionMode.INCREMENTAL, it) })
        }
    }

    private class FakeBackfill(private val nextCursor: Map<String, String>) : BackfillConnector {

        override val id = ConnectorId("sejm")

        override fun partitions(from: LocalDate, to: LocalDate) = listOf(BackfillPartition("term10", "term10"))

        override fun readPartitionChunk(
            partition: BackfillPartition,
            cursor: Cursor?,
            sink: RawDocumentSink,
        ): FetchResult = FetchResult(nextCursor = Cursor(IngestionMode.BACKFILL, nextCursor))
    }

    private object SilentEvents : ApplicationEventPublisher {
        override fun publishEvent(event: ApplicationEvent) = Unit

        override fun publishEvent(event: Any) = Unit
    }

    private companion object {
        /** The same address twice is the same content twice, which the archive stores once. */
        fun payloadFor(externalId: String) = RawPayload(
            externalId = ExternalId(externalId),
            payload = "{\"id\":\"$externalId\"}".toByteArray(),
            kind = PayloadKind.JSON,
        )
    }
}
