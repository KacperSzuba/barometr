package pl.barometr.ingestion.api

import pl.barometr.sources.api.ConnectorId

interface ArchiveCompleteness {
    fun compareArchiveAgainstSource(connectorId: ConnectorId): CompletenessReport
}
