package pl.barometr.ingestion.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind
import pl.barometr.sources.api.ConnectorId

/** The connector exists but reads no archive: it implements no backfill. */
class BackfillNotSupportedException(val connectorId: ConnectorId) :
    DomainException(ErrorKind.INVALID, "backfill_not_supported")
