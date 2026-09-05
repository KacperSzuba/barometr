package pl.barometr.identity.api

import java.time.Instant
import java.util.UUID

/**
 * Published when an account is signed in on something it has not been signed in on
 * before.
 *
 * The one notification a security-conscious product owes without being asked: somebody
 * whose password has been taken finds out from a message about a device they do not
 * recognise, and from nothing else. Identity raises it and takes no view on how anybody
 * is told — that belongs to the context that owns delivery, suppression and the
 * consequences of sending mail.
 *
 * [sessionId] is the session's identifier as the account's own device list shows it, so
 * whatever is sent can say "end this session" and mean something the reader can act on.
 */
data class SignedInFromNewDevice(
    val userId: UserId,
    val sessionId: UUID,
    /** As the client sent it, unparsed — a user agent is not a device name. */
    val userAgent: String?,
    val clientIp: String?,
    /** Roughly where that address is — `Warszawa, PL` — or null when nobody can say. */
    val approximateLocation: String?,
    val occurredAt: Instant,
)
