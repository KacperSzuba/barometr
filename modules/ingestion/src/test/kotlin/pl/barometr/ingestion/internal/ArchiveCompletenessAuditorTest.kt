package pl.barometr.ingestion.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import pl.barometr.ingestion.api.AuditableConnector
import pl.barometr.ingestion.api.BackfillPartition
import pl.barometr.ingestion.api.DeclaredVolume
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.ingestion.api.PayloadKind
import pl.barometr.ingestion.api.RawPayload
import pl.barometr.ingestion.internal.jooq.tables.references.RAW_DOCUMENT
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.IngestionCursors
import pl.barometr.sources.api.IngestionMode
import pl.barometr.sources.api.SourceDefinition
import pl.barometr.sources.api.SourceId
import pl.barometr.sources.api.SourceRegistry
import pl.barometr.storage.internal.FilesystemBlobStore
import pl.barometr.storage.internal.StorageProperties
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The audit turns "the backfill reported success" into "the archive holds what the
 * source says it holds" — the only way to catch a replay that dropped records, since
 * every individual run of such a replay reports success.
 */
class ArchiveCompletenessAuditorTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dsl()
    private val clock = TestClock()
    private val sourceId = SourceId(Ids.next())
    private lateinit var archiver: RawDocumentArchiver

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(RAW_DOCUMENT).execute()
        archiver = RawDocumentArchiver(
            blobs = FilesystemBlobStore(StorageProperties(blobRoot)),
            documents = RawDocumentRepository(dsl, clock),
            events = SilentEvents(),
            clock = clock,
        )
    }

    @Test
    fun `a complete archive reports no gaps`() {
        archivePrints(count = 100)

        val report = auditorFor(declaredPrints = 100).compareArchiveAgainstSource(CONNECTOR)

        assertTrue(report.gaps.isEmpty())
        assertTrue(report.isComplete)
        assertEquals(100, report.findings.single().archived)
    }

    /** The spec's threshold: a gap under half a percent is tolerated. */
    @Test
    fun `a gap within tolerance is not a gap`() {
        archivePrints(count = 997)

        val report = auditorFor(declaredPrints = 1000).compareArchiveAgainstSource(CONNECTOR)

        // 3 of 1000 missing is 0.3%, under the 0.5% tolerance.
        assertTrue(report.gaps.isEmpty())
        assertTrue(report.isComplete)
    }

    @Test
    fun `a gap beyond tolerance is reported`() {
        archivePrints(count = 900)

        val report = auditorFor(declaredPrints = 1000).compareArchiveAgainstSource(CONNECTOR)

        val gap = report.gaps.single()
        assertEquals(1000, gap.declared)
        assertEquals(900, gap.archived)
        assertEquals(0.1, gap.missingFraction)
        assertFalse(report.isComplete)
    }

    /**
     * Holding more than declared is not a fault: prints get revised, and the source's
     * tally can lag behind its own list.
     */
    @Test
    fun `holding more than declared is not a gap`() {
        archivePrints(count = 120)

        val report = auditorFor(declaredPrints = 100).compareArchiveAgainstSource(CONNECTOR)

        assertTrue(report.gaps.isEmpty())
        assertTrue(report.findings.single().missingFraction < 0)
    }

    /**
     * The distinction that stops the report being falsely reassuring: a count derived
     * from the same list we ingested proves the walk finished, not that the archive is
     * complete. On its own it cannot establish completeness.
     */
    @Test
    fun `a non-authoritative match alone does not establish completeness`() {
        archiveProceedings(count = 40)

        val report = auditorFor(declaredProceedings = 40).compareArchiveAgainstSource(CONNECTOR)

        assertTrue(report.gaps.isEmpty())
        assertFalse(report.isComplete, "only an authoritative count can prove completeness")
    }

    /**
     * Regression: external ids are hierarchical, so counting `term10/proceeding/` by
     * plain prefix also counted every voting beneath it — 974 where 75 was right.
     */
    @Test
    fun `documents nested under a prefix are not counted as that prefix`() {
        archiveProceedings(count = 40)
        archiveVotingsUnderProceedings(proceedings = 40, votingsEach = 20)

        val report = auditorFor(declaredProceedings = 40).compareArchiveAgainstSource(CONNECTOR)

        assertEquals(40, report.findings.single().archived, "votings must not inflate the count")
    }

    @Test
    fun `only replayed partitions are audited`() {
        archivePrints(count = 10)

        // No backfill cursor recorded, so nothing has been replayed.
        val auditor = auditorFor(declaredPrints = 1000, replayedPartitions = emptySet())

        assertTrue(auditor.compareArchiveAgainstSource(CONNECTOR).findings.isEmpty())
    }

    // ——— Fixtures ————————————————————————————————————————————————————————————

    private fun archivePrints(count: Int) = repeat(count) { index ->
        archiver.archive(
            sourceId,
            runId = null,
            payload = RawPayload(
                ExternalId("term10/print/$index"),
                """{"number":"$index"}""".toByteArray(),
                PayloadKind.JSON,
            ),
        )
    }

    private fun archiveProceedings(count: Int) = repeat(count) { index ->
        archiver.archive(
            sourceId,
            runId = null,
            payload = RawPayload(
                ExternalId("term10/proceeding/$index"),
                """{"number":$index}""".toByteArray(),
                PayloadKind.JSON,
            ),
        )
    }

    private fun archiveVotingsUnderProceedings(proceedings: Int, votingsEach: Int) =
        repeat(proceedings) { proceeding ->
            repeat(votingsEach) { voting ->
                archiver.archive(
                    sourceId,
                    runId = null,
                    payload = RawPayload(
                        ExternalId("term10/proceeding/$proceeding/voting/$voting"),
                        """{"votingNumber":$voting,"sitting":$proceeding}""".toByteArray(),
                        PayloadKind.JSON,
                    ),
                )
            }
        }

    private fun auditorFor(
        declaredPrints: Int? = null,
        declaredProceedings: Int? = null,
        replayedPartitions: Set<String> = setOf(PARTITION),
    ) = ArchiveCompletenessAuditor(
        connectors = ConnectorRegistry(
            listOf(FakeAuditableConnector(declaredPrints, declaredProceedings)),
        ),
        sources = FakeSourceRegistry(sourceId),
        cursors = FakeCursors(replayedPartitions),
        documents = RawDocumentRepository(dsl, clock),
        properties = IngestionProperties(),
    )

    private class FakeAuditableConnector(
        private val declaredPrints: Int?,
        private val declaredProceedings: Int?,
    ) : AuditableConnector {

        override val id = CONNECTOR

        override fun declaredVolumes(partition: BackfillPartition) = buildList {
            declaredPrints?.let {
                add(DeclaredVolume(partition.key, "print", "term10/print/", it, isAuthoritative = true))
            }
            declaredProceedings?.let {
                add(
                    DeclaredVolume(
                        partition.key, "proceeding", "term10/proceeding/", it,
                        isAuthoritative = false,
                    ),
                )
            }
        }
    }

    private class FakeSourceRegistry(private val sourceId: SourceId) : SourceRegistry {
        private val definition = SourceDefinition(
            id = sourceId,
            connectorId = CONNECTOR,
            name = "test",
            baseUrl = URI.create("https://example.test"),
            refreshInterval = Duration.ofMinutes(15),
            expectedMinRecordsPerRun = null,
        )

        override fun enabled() = listOf(definition)

        override fun byConnector(connectorId: ConnectorId) =
            definition.takeIf { connectorId == CONNECTOR }

        override fun enabledById(id: SourceId) = definition.takeIf { id == sourceId }
    }

    private class FakeCursors(private val replayed: Set<String>) : IngestionCursors {
        override fun load(sourceId: SourceId, mode: IngestionMode, partition: String) = null

        override fun save(
            sourceId: SourceId,
            mode: IngestionMode,
            position: Map<String, String>,
            partition: String,
        ) = Unit

        override fun partitions(sourceId: SourceId, mode: IngestionMode) =
            replayed.associateWith { mapOf("done" to "true") }
    }

    private class SilentEvents : ApplicationEventPublisher {
        override fun publishEvent(event: ApplicationEvent) = Unit

        override fun publishEvent(event: Any) = Unit
    }

    private companion object {
        val CONNECTOR = ConnectorId("test-source")
        const val PARTITION = "term10"
    }
}
