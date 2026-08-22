package pl.barometr.legislative.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.legislative.internal.jooq.tables.references.ACT
import pl.barometr.legislative.internal.jooq.tables.references.ACT_IDENTIFIER
import pl.barometr.legislative.internal.jooq.tables.references.ACT_REFERENCE
import pl.barometr.shared.ContentHash
import pl.barometr.shared.Eli
import pl.barometr.shared.Ids
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.SourceId
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.internal.FilesystemBlobStore
import pl.barometr.storage.internal.StorageProperties
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A published act becoming the identity everything else is matched against.
 *
 * Against a real database, because what is being tested is mostly what the schema
 * refuses: one act per ELI however many times it is read, one act per identifier, and
 * a reference graph that is replaced rather than appended to.
 */
class EliActProjectorTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()

    private lateinit var blobs: FilesystemBlobStore
    private lateinit var meters: SimpleMeterRegistry
    private lateinit var projector: EliActProjector

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(ACT_REFERENCE).execute()
        dsl.deleteFrom(ACT_IDENTIFIER).execute()
        dsl.deleteFrom(ACT).execute()

        blobs = FilesystemBlobStore(StorageProperties(blobRoot))
        meters = SimpleMeterRegistry()
        projector = EliActProjector(
            blobs = blobs,
            reader = EliActReader(json),
            acts = ActRepository(dsl, clock),
            identifiers = ActIdentifierRepository(dsl, clock),
            references = ActReferenceRepository(dsl, clock),
            events = RecordingEventPublisher(),
            meters = meters,
            clock = clock,
        )
    }

    @Test
    fun `a published act becomes a row keyed on its ELI`() {
        projector.projectPublishedAct(archivedAct())

        val act = assertNotNull(dsl.selectFrom(ACT).fetchOne())
        assertEquals("DU/2026/1074", act.eli)
        assertEquals("DU", act.publisher)
        assertEquals("Ustawa", act.actType)
        assertEquals(LocalDate.parse("2026-08-10"), act.announcedOn)
        assertEquals(LocalDate.parse("2027-02-11"), act.inForceFrom)
        // Stored normalised, because the trigram index is built on this column and a
        // query normalised differently would search a different alphabet.
        assertTrue(act.titleNormalised!!.startsWith("ustawa z dnia 17 lipca 2026 r o zmianie"))
    }

    /**
     * The link that makes identity resolution work at all: it is stated by the
     * publisher, so most documents reach their act without a title ever being compared.
     */
    @Test
    fun `the Sejm print the act came from is pinned to it`() {
        projector.projectPublishedAct(archivedAct())

        val print = assertNotNull(
            dsl.selectFrom(ACT_IDENTIFIER)
                .where(ACT_IDENTIFIER.SCHEME.eq(IdentifierScheme.SEJM_PRINT.wireName))
                .fetchOne(),
        )
        assertEquals("term10/print/2620", print.value)
        assertEquals(MatchMethod.EXACT.wireName, print.resolvedBy)

        val eli = assertNotNull(
            dsl.selectFrom(ACT_IDENTIFIER)
                .where(ACT_IDENTIFIER.SCHEME.eq(IdentifierScheme.ELI.wireName))
                .fetchOne(),
        )
        assertEquals("DU/2026/1074", eli.value)
        assertEquals(print.actId, eli.actId, "both identifiers must resolve to one act")
    }

    @Test
    fun `the change graph records what the act did, in the direction it did it`() {
        projector.projectPublishedAct(archivedAct())

        val edges = dsl.selectFrom(ACT_REFERENCE).fetch()

        assertEquals(8, edges.size)
        assertTrue(edges.all { it.fromEli == "DU/2026/1074" })
        assertEquals(6, edges.count { it.relation == ActRelation.AMENDS.wireName })
        assertEquals(2, edges.count { it.relation == ActRelation.REPEALS.wireName })
        // The point of keying on ELI: the amended acts are from 2000 to 2022, none of
        // which a five-year archive holds. Under a foreign key these edges would not
        // exist at all.
        assertTrue(edges.any { it.toEli == "DU/2000/1099" })
    }

    /**
     * The register restates an act whenever anything about it changes, and Spring
     * Modulith redelivers anything a listener did not finish. Both must land on the
     * same row.
     */
    @Test
    fun `reading the same act twice leaves one act and one set of references`() {
        projector.projectPublishedAct(archivedAct())
        projector.projectPublishedAct(archivedAct())

        assertEquals(1, dsl.fetchCount(ACT))
        assertEquals(2, dsl.fetchCount(ACT_IDENTIFIER))
        assertEquals(8, dsl.fetchCount(ACT_REFERENCE))
    }

    @Test
    fun `a document from another source is not projected as an act`() {
        projector.projectPublishedAct(archivedAct().copy(connectorId = ConnectorId("sejm")))

        assertEquals(0, dsl.fetchCount(ACT))
    }

    private fun archivedAct(): DocumentVersionRecorded {
        val payload = requireNotNull(javaClass.getResourceAsStream("/fixtures/isap/act-with-prints.json"))
            .use { it.readBytes() }
        val stored = blobs.store(BlobBucket.RAW, payload, "application/json")

        return DocumentVersionRecorded(
            documentId = DocumentId(Ids.next()),
            versionId = DocumentVersionId(Ids.next()),
            sourceId = SourceId(Ids.next()),
            connectorId = ConnectorId("isap"),
            externalId = ExternalId(Eli("DU/2026/1074").value),
            kind = DocumentKind("act"),
            contentHash = ContentHash.of(payload),
            versionNo = 1,
            occurredAt = clock.instant(),
        )
    }

    /** Records what the projector announced; nothing here asserts on it yet. */
    private class RecordingEventPublisher : org.springframework.context.ApplicationEventPublisher {
        val published = mutableListOf<Any>()

        override fun publishEvent(event: Any) {
            published += event
        }
    }
}
