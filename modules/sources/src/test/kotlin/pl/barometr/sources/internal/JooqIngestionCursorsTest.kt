package pl.barometr.sources.internal

import org.jooq.JSONB
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.sources.api.IngestionMode
import pl.barometr.sources.api.SourceId
import pl.barometr.sources.internal.jooq.tables.references.INGESTION_CURSOR
import pl.barometr.sources.internal.jooq.tables.references.SOURCE
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Where each connector left off, against a real database.
 *
 * Two things are being pinned down and neither is visible in Kotlin. A position is a
 * position and not a history, so writing it twice must leave one row — the natural key
 * and its upsert are what enforce that. And a backfill partition's position is its own:
 * saved anywhere else, resuming one parliamentary term would resume from where another
 * left off, which is the kind of defect that shows up months later as a gap in the
 * archive.
 */
class JooqIngestionCursorsTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()
    private val cursors = JooqIngestionCursors(dsl, json, clock)

    // The registry's own row for the Sejm: the cursor table references it, so the
    // position has to belong to a source that exists.
    private val sejm = SourceId(
        dsl.select(SOURCE.ID).from(SOURCE).where(SOURCE.CONNECTOR_ID.eq("sejm")).fetchSingle().value1()!!,
    )

    @BeforeEach
    fun clearPositions() {
        dsl.deleteFrom(INGESTION_CURSOR).execute()
    }

    @Test
    fun `a position is read back exactly as the connector wrote it`() {
        cursors.save(sejm, IngestionMode.INCREMENTAL, mapOf("changedThrough" to "2026-08-21", "term" to "10"))

        assertEquals(
            mapOf("changedThrough" to "2026-08-21", "term" to "10"),
            cursors.load(sejm, IngestionMode.INCREMENTAL),
        )
    }

    @Test
    fun `a source that has never run has no position`() {
        assertNull(cursors.load(sejm, IngestionMode.INCREMENTAL))
    }

    /** A cursor is a position, not a history: an old one is only something to read by mistake. */
    @Test
    fun `saving again moves the position rather than adding a second`() {
        cursors.save(sejm, IngestionMode.INCREMENTAL, mapOf("changedThrough" to "2026-08-01"))
        cursors.save(sejm, IngestionMode.INCREMENTAL, mapOf("changedThrough" to "2026-08-21"))

        assertEquals(1, dsl.fetchCount(INGESTION_CURSOR), "one position per source, mode and partition")
        assertEquals(mapOf("changedThrough" to "2026-08-21"), cursors.load(sejm, IngestionMode.INCREMENTAL))
    }

    @Test
    fun `each backfill partition remembers its own position`() {
        cursors.save(sejm, IngestionMode.BACKFILL, mapOf("offset" to "500"), partition = "term10")
        cursors.save(sejm, IngestionMode.BACKFILL, mapOf("offset" to "1500"), partition = "term9")

        assertEquals(mapOf("offset" to "500"), cursors.load(sejm, IngestionMode.BACKFILL, "term10"))
        assertEquals(mapOf("offset" to "1500"), cursors.load(sejm, IngestionMode.BACKFILL, "term9"))
    }

    @Test
    fun `the two modes do not share a position`() {
        cursors.save(sejm, IngestionMode.INCREMENTAL, mapOf("changedThrough" to "2026-08-21"))
        cursors.save(sejm, IngestionMode.BACKFILL, mapOf("offset" to "500"), partition = "term10")

        assertEquals(mapOf("changedThrough" to "2026-08-21"), cursors.load(sejm, IngestionMode.INCREMENTAL))
        assertNull(cursors.load(sejm, IngestionMode.INCREMENTAL, "term10"))
    }

    /**
     * What the dispatcher asks to find a replay with work left in it: the partitions,
     * and never the single unpartitioned position beside them.
     */
    @Test
    fun `the partitions are listed without the incremental position among them`() {
        cursors.save(sejm, IngestionMode.BACKFILL, mapOf("offset" to "500"), partition = "term10")
        cursors.save(sejm, IngestionMode.BACKFILL, mapOf("done" to "true"), partition = "term9")
        cursors.save(sejm, IngestionMode.BACKFILL, mapOf("offset" to "0"))

        assertEquals(
            mapOf("term10" to mapOf("offset" to "500"), "term9" to mapOf("done" to "true")),
            cursors.partitions(sejm, IngestionMode.BACKFILL),
        )
    }

    /**
     * The typed decode, tried against the shape it exists to refuse. A position holding
     * a number used to be read with a cast and fail as a `ClassCastException` wherever
     * the value was eventually used, far from the row that caused it.
     */
    @Test
    fun `a stored position that is not a map of strings is refused where it is read`() {
        dsl.insertInto(INGESTION_CURSOR)
            .set(INGESTION_CURSOR.SOURCE_ID, sejm.value)
            .set(INGESTION_CURSOR.MODE, IngestionMode.INCREMENTAL.wireName)
            .set(INGESTION_CURSOR.PARTITION, "")
            .set(INGESTION_CURSOR.POSITION, JSONB.valueOf("{\"term\": {\"number\": 10}}"))
            .set(INGESTION_CURSOR.UPDATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .execute()

        assertFailsWith<Exception> { cursors.load(sejm, IngestionMode.INCREMENTAL) }
    }
}
