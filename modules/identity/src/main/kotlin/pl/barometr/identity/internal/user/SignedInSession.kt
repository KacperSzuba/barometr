package pl.barometr.identity.internal.user

import java.time.Instant
import java.util.UUID

/**
 * One login, as the person who made it sees it.
 *
 * The identity is the refresh-token family: a login issues one family and every token
 * that descends from it belongs to the same device. Ending a session is therefore the
 * same operation as revoking a family, which is the operation replay detection already
 * uses — one mechanism, two reasons to reach for it.
 *
 * [userAgent] is the string the client sent, unparsed. [clientIp] is kept because "signed
 * in from an address you have never used" is the whole reason this list exists.
 */
data class SignedInSession(
    val familyId: UUID,
    val userId: UUID,
    val userAgent: String?,
    val clientIp: String?,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val revokedAt: Instant? = null,
)
