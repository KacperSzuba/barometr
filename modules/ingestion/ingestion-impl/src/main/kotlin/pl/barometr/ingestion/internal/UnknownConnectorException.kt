package pl.barometr.ingestion.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind
import pl.barometr.sources.api.ConnectorId

/**
 * Asked to work with a connector that has no source registered.
 *
 * A `DomainException` rather than `error(...)`: this is reachable from a query
 * parameter, and a caller's typo should not be reported as a server fault.
 */
class UnknownConnectorException(val connectorId: ConnectorId) :
    DomainException(ErrorKind.NOT_FOUND, "unknown_connector")
