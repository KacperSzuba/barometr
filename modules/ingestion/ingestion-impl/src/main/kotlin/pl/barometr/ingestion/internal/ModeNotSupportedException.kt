package pl.barometr.ingestion.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.IngestionMode

/**
 * A run was requested in a mode this connector does not implement.
 *
 * Reachable when a registry row outlives the connector's capabilities — the row
 * says a source is read incrementally, the connector no longer implements
 * [pl.barometr.ingestion.api.IncrementalConnector]. Previously this was an
 * unchecked cast, so the same situation was a `ClassCastException` raised after a
 * run row had already been opened.
 */
class ModeNotSupportedException(val connectorId: ConnectorId, val mode: IngestionMode) :
    DomainException(ErrorKind.INVALID, "mode_not_supported")
