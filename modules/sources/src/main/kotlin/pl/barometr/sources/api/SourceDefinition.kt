package pl.barometr.sources.api

import java.net.URI
import java.time.Duration

data class SourceDefinition(
    val id: SourceId,
    val connectorId: ConnectorId,
    val name: String,
    val baseUrl: URI,
    val refreshInterval: Duration,
    /**
     * What a healthy run looks like. Without a baseline, a source answering
     * HTTP 200 with zero records is indistinguishable from a quiet day — and that
     * is the most common failure in this class of system.
     */
    val expectedMinRecordsPerRun: Int?,
)
