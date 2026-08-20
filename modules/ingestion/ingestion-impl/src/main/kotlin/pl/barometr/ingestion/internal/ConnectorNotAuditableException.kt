package pl.barometr.ingestion.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind
import pl.barometr.sources.api.ConnectorId

/**
 * The connector publishes no counts to check the archive against.
 *
 * Distinct from a connector that is simply missing: there is nothing wrong with a
 * source that does not state its own tally, and the honest answer is that this
 * report cannot be produced — not an invented number.
 */
class ConnectorNotAuditableException(val connectorId: ConnectorId) :
    DomainException(ErrorKind.INVALID, "connector_not_auditable")
