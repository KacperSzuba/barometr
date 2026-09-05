package pl.barometr.identity.internal.workspace

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * A workspace keeps at least one owner. Removing the last one leaves an account nobody can pay for, rename or close.
 */
class LastOwnerException : DomainException(ErrorKind.CONFLICT, "last_owner")
