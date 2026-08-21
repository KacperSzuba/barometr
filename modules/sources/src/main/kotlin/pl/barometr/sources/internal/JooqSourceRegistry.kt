package pl.barometr.sources.internal

import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.SourceDefinition
import pl.barometr.sources.api.SourceId
import pl.barometr.sources.api.SourceRegistry
import pl.barometr.sources.internal.jooq.tables.references.SOURCE
import java.net.URI

@Repository
@Transactional(readOnly = true)
class JooqSourceRegistry(private val dsl: DSLContext) : SourceRegistry {

    /**
     * Only enabled sources. The schema will not let a source be enabled without a
     * legal basis recorded, so "enabled" already means "cleared for production".
     */
    override fun enabled(): List<SourceDefinition> =
        dsl.selectFrom(SOURCE)
            .where(SOURCE.ENABLED.isTrue)
            .orderBy(SOURCE.CONNECTOR_ID)
            .fetch(::toDefinition)

    override fun byConnector(connectorId: ConnectorId): SourceDefinition? =
        dsl.selectFrom(SOURCE)
            .where(SOURCE.CONNECTOR_ID.eq(connectorId.value))
            .fetchOne(::toDefinition)

    override fun enabledById(id: SourceId): SourceDefinition? =
        dsl.selectFrom(SOURCE)
            .where(SOURCE.ID.eq(id.value))
            .and(SOURCE.ENABLED.isTrue)
            .fetchOne(::toDefinition)

    override fun byId(id: SourceId): SourceDefinition? =
        dsl.selectFrom(SOURCE)
            .where(SOURCE.ID.eq(id.value))
            .fetchOne(::toDefinition)

    private fun toDefinition(record: Record) = SourceDefinition(
        id = SourceId(record[SOURCE.ID]!!),
        connectorId = ConnectorId(record[SOURCE.CONNECTOR_ID]!!),
        name = record[SOURCE.NAME]!!,
        baseUrl = URI.create(record[SOURCE.BASE_URL]!!),
        refreshInterval = record[SOURCE.REFRESH_INTERVAL]!!.toDuration(),
        expectedMinRecordsPerRun = record[SOURCE.EXPECTED_MIN_RECORDS_PER_RUN],
    )
}
