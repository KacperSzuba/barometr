package pl.barometr.ingestion.api

import pl.barometr.sources.api.ConnectorId
import java.time.LocalDate

/**
 * Starts a historical replay.
 *
 * Deliberately explicit rather than automatic. A five-year backfill is thousands
 * of requests to somebody else's server; it belongs to a decision someone makes,
 * not to something that happens because the application restarted.
 */
interface BackfillLauncher {
    fun launch(connectorId: ConnectorId, from: LocalDate, to: LocalDate): BackfillPlan
}
