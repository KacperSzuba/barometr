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
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_IDENTIFIER
import pl.barometr.legislative.internal.jooq.tables.references.STAGE_TRANSITION
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
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A process becoming a draft with a history, against a real database — because most
 * of what is being tested is what the schema refuses: one draft per print however
 * many times the register restates it, and a history that grows only where something
 * actually changed.
 */
class LegislativePathProjectorTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dsl()
    private val clock = TestClock()
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()

    private lateinit var blobs: FilesystemBlobStore
    private lateinit var meters: SimpleMeterRegistry
    private lateinit var acts: ActRepository
    private lateinit var actIdentifiers: ActIdentifierRepository
    private lateinit var projector: LegislativePathProjector

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(STAGE_TRANSITION).execute()
        dsl.deleteFrom(DRAFT_IDENTIFIER).execute()
        dsl.deleteFrom(DRAFT).execute()
        dsl.deleteFrom(ACT_IDENTIFIER).execute()
        dsl.deleteFrom(ACT).execute()

        blobs = FilesystemBlobStore(StorageProperties(blobRoot))
        meters = SimpleMeterRegistry()
        acts = ActRepository(dsl, clock)
        actIdentifiers = ActIdentifierRepository(dsl, clock)
        projector = LegislativePathProjector(
            blobs = blobs,
            reader = SejmProcessReader(json),
            drafts = DraftRepository(dsl, clock),
            identifiers = DraftIdentifierRepository(dsl, clock),
            transitions = StageTransitionRepository(dsl, clock),
            acts = actIdentifiers,
            events = RecordingEventPublisher(),
            meters = meters,
            clock = clock,
        )
    }

    @Test
    fun `a bill becomes a draft addressed by the print it arrived as`() {
        projector.projectDraftPath(archivedProcess("process-31.json"))

        val draft = assertNotNull(dsl.selectFrom(DRAFT).fetchOne())
        assertEquals(DraftInitiator.CITIZENS.wireName, draft.initiator)
        assertEquals(10, draft.term)
        assertEquals(DraftOutcome.ENACTED.wireName, draft.outcome)
        assertEquals(LocalDate.parse("2023-11-29"), draft.closedOn)

        val identifier = assertNotNull(dsl.selectFrom(DRAFT_IDENTIFIER).fetchOne())
        assertEquals("term10/print/31", identifier.value)
        assertEquals(draft.id, identifier.draftId)
    }

    /**
     * The question this schema was designed around: what was the status of draft X on
     * day Y, answerable in SQL and nowhere else.
     */
    @Test
    fun `the history answers where the draft was on a given day`() {
        projector.projectDraftPath(archivedProcess("process-31.json"))

        assertEquals(
            listOf(LegislativeStage.FIRST_READING.wireName, LegislativeStage.COMMITTEE_WORK.wireName),
            stagesOn("2023-11-28"),
            "the first reading and the committee work that followed it fell on one day",
        )
        assertEquals(
            listOf(
                LegislativeStage.SECOND_READING.wireName,
                LegislativeStage.COMMITTEE_WORK.wireName,
                LegislativeStage.THIRD_READING.wireName,
            ),
            stagesOn("2023-11-29"),
            "three stages fell on that day, and the register does not time them",
        )
        assertEquals(listOf(LegislativeStage.SENATE_POSITION.wireName), stagesOn("2023-12-10"))
        assertEquals(listOf(LegislativeStage.PRESIDENT_SIGNED.wireName), stagesOn("2024-06-01"))
    }

    @Test
    fun `every recorded stage cites the document version that stated it`() {
        val recorded = archivedProcess("process-31.json")

        projector.projectDraftPath(recorded)

        val transitions = dsl.selectFrom(STAGE_TRANSITION).fetch()
        assertEquals(10, transitions.size, "ten dated stages; the closing verdict has no date")
        assertTrue(transitions.all { it.sourceDocumentVersionId == recorded.versionId.value })
        assertTrue(transitions.all { it.sourceLabel != null }, "the register's own words are kept")
    }

    /**
     * The register restates a whole history every time anything moves, and Spring
     * Modulith redelivers anything a listener did not finish. Neither may pile copies
     * onto the history.
     */
    @Test
    fun `restating the same history records nothing new`() {
        projector.projectDraftPath(archivedProcess("process-31.json"))
        val afterFirst = dsl.fetchCount(STAGE_TRANSITION)

        projector.projectDraftPath(archivedProcess("process-31.json"))

        assertEquals(afterFirst, dsl.fetchCount(STAGE_TRANSITION))
        assertEquals(1, dsl.fetchCount(DRAFT), "one draft per print, however often it is restated")
    }

    /**
     * A bill still moving has an open period at its last stage — and when the next
     * stage arrives, the closed version of that period is a new fact recorded beside
     * the open one rather than an overwrite of it.
     */
    @Test
    fun `a stage still current is recorded with an open period`() {
        projector.projectDraftPath(archivedProcess("process-27.json"))

        val open = dsl.selectFrom(STAGE_TRANSITION)
            .where(STAGE_TRANSITION.VALID_TO.isNull)
            .fetch()

        assertEquals(1, open.size)
        assertEquals(LegislativeStage.FIRST_READING.wireName, open.single().stage)
    }

    @Test
    fun `a process that is not a draft is counted rather than projected`() {
        projector.projectDraftPath(archivedProcess("process-3.json"))

        assertEquals(0, dsl.fetchCount(DRAFT))
        assertEquals(1.0, meters.counter("legislative.process.skipped", "reason", "not-a-draft").count())
    }

    /**
     * The register states a draft's ELI at publication, usually before the act itself
     * has been read — so whichever of the two arrives second makes the link.
     */
    @Test
    fun `a draft is joined to the act it became once that act is known`() {
        projector.projectDraftPath(archivedProcess("process-31.json"))
        assertNull(dsl.selectFrom(DRAFT).fetchOne()?.actId, "no act has been read yet")

        val actId = acts.actFor(
            EliActMetadata(
                eli = Eli("DU/2023/2730"),
                title = "Ustawa z dnia 29 listopada 2023 r. o zmianie ustawy o świadczeniach",
                type = "Ustawa",
                announcedOn = LocalDate.parse("2023-12-20"),
                inForceFrom = null,
                prints = emptyList(),
                references = emptyList(),
                unmappedLabels = emptyList(),
            ),
        )
        actIdentifiers.pointAtAct(IdentifierScheme.ELI, "DU/2023/2730", actId, MatchMethod.EXACT, 1.0)

        projector.projectDraftPath(archivedProcess("process-31.json"))

        assertEquals(actId.value, dsl.selectFrom(DRAFT).fetchOne()?.actId)
    }

    // ——— Harness ————————————————————————————————————————————————————————————

    /** Stage names current on a day, in the register's own order. */
    private fun stagesOn(date: String): List<String> {
        val day = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()

        return dsl.selectFrom(STAGE_TRANSITION)
            .where(STAGE_TRANSITION.VALID_FROM.le(day))
            .and(STAGE_TRANSITION.VALID_TO.isNull.or(STAGE_TRANSITION.VALID_TO.gt(day)))
            .orderBy(STAGE_TRANSITION.VALID_FROM, STAGE_TRANSITION.ORDINAL)
            .fetch(STAGE_TRANSITION.STAGE)
            .filterNotNull()
    }

    private fun archivedProcess(fixture: String): DocumentVersionRecorded {
        val payload = requireNotNull(javaClass.getResourceAsStream("/fixtures/sejm/$fixture"))
            .use { it.readBytes() }
        val stored = blobs.store(BlobBucket.RAW, payload, "application/json")

        return DocumentVersionRecorded(
            documentId = DocumentId(Ids.next()),
            versionId = DocumentVersionId(Ids.next()),
            sourceId = SourceId(Ids.next()),
            connectorId = ConnectorId("sejm"),
            externalId = ExternalId("term10/process/31"),
            kind = DocumentKind("process"),
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
