package pl.barometr.alerts.internal

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * A feed token that names nothing: never minted, or revoked since.
 *
 * A calendar client that gets a 404 stops fetching and, in most clients, says so to the
 * person who subscribed — which is what a revoked subscription should look like.
 */
class UnknownCalendarFeedException : DomainException(ErrorKind.NOT_FOUND, "unknown_calendar_feed")
