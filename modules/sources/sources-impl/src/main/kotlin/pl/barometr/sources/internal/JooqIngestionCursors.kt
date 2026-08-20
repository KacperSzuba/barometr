package pl.barometr.sources.internal

import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.sources.api.IngestionCursors
import pl.barometr.sources.api.IngestionMode
import pl.barometr.sources.api.SourceId
import pl.barometr.sources.internal.jooq.tables.references.INGESTION_CURSOR
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
@Transactional
class JooqIngestionCursors(
    private val dsl: DSLContext,
    private val json: ObjectMapper,
    private val clock: Clock,
) : IngestionCursors {

    @Transactional(readOnly = true)
    override fun load(
        sourceId: SourceId,
        mode: IngestionMode,
        partition: String,
    ): Map<String, String>? =
        dsl.select(INGESTION_CURSOR.POSITION)
            .from(INGESTION_CURSOR)
            .where(INGESTION_CURSOR.SOURCE_ID.eq(sourceId.value))
            .and(INGESTION_CURSOR.MODE.eq(mode.wireName))
            .and(INGESTION_CURSOR.PARTITION.eq(partition))
            .fetchOne()
            ?.value1()
            ?.let(::decodePosition)

    @Transactional(readOnly = true)
    override fun partitions(
        sourceId: SourceId,
        mode: IngestionMode,
    ): Map<String, Map<String, String>> =
        dsl.select(INGESTION_CURSOR.PARTITION, INGESTION_CURSOR.POSITION)
            .from(INGESTION_CURSOR)
            .where(INGESTION_CURSOR.SOURCE_ID.eq(sourceId.value))
            .and(INGESTION_CURSOR.MODE.eq(mode.wireName))
            .and(INGESTION_CURSOR.PARTITION.ne(""))
            .fetch()
            .associate { record -> record.value1()!! to decodePosition(record.value2()!!) }

    override fun save(
        sourceId: SourceId,
        mode: IngestionMode,
        position: Map<String, String>,
        partition: String,
    ) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val encoded = JSONB.valueOf(json.writeValueAsString(position))

        // Upsert on the natural key. A cursor is a position, not a history: keeping
        // old ones would only invite reading a stale row by accident.
        dsl.insertInto(INGESTION_CURSOR)
            .set(INGESTION_CURSOR.SOURCE_ID, sourceId.value)
            .set(INGESTION_CURSOR.MODE, mode.wireName)
            .set(INGESTION_CURSOR.PARTITION, partition)
            .set(INGESTION_CURSOR.POSITION, encoded)
            .set(INGESTION_CURSOR.UPDATED_AT, now)
            .onConflict(INGESTION_CURSOR.SOURCE_ID, INGESTION_CURSOR.MODE, INGESTION_CURSOR.PARTITION)
            .doUpdate()
            .set(INGESTION_CURSOR.POSITION, encoded)
            .set(INGESTION_CURSOR.UPDATED_AT, now)
            .execute()
    }

    /**
     * Reads a stored position back into the map a connector wrote.
     *
     * Typed rather than cast: `readValue(…, Map::class.java) as Map<String, String>`
     * accepts a position holding a number or a nested object and then fails as a
     * `ClassCastException` wherever the value is eventually read — far from the row
     * that caused it. This fails here, naming the column.
     */
    private fun decodePosition(raw: JSONB): Map<String, String> = json.readValue(raw.data())
}
