package pl.barometr.identity.internal.workspace

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * Every seat is taken or promised. An invitation that has gone out is a seat somebody has already been offered, which is why revoking one frees it.
 */
class NoSeatsLeftException : DomainException(ErrorKind.CONFLICT, "no_seats_left")
