package pl.barometr.ingestion.api

import pl.barometr.sources.api.ConnectorId

data class BackfillPlan(
    val connectorId: ConnectorId,
    /** Newest first, so an interrupted replay already holds the useful years. */
    val partitions: List<BackfillPartition>,
    val queued: Int,
    /** Already pending or running, so not queued again. */
    val skipped: Int,
)
