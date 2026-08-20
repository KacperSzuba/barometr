package pl.barometr.ingestion.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/** The requested replay window ends before it starts. */
class InvalidBackfillWindowException :
    DomainException(ErrorKind.INVALID, "invalid_backfill_window")
