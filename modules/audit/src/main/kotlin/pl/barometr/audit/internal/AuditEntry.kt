package pl.barometr.audit.internal

import pl.barometr.audit.api.AuditOutcome
import pl.barometr.identity.api.UserId
import java.time.Instant

/** One recorded attempt, as it comes back out. */
data class AuditEntry(
    val sequence: Long,
    val at: Instant,
    val actor: UserId?,
    val actorLabel: String?,
    val action: String,
    val resource: String,
    val outcome: AuditOutcome,
    val status: Int?,
    val peer: String?,
    /** Why, on the entries no request explains. */
    val detail: String?,
    val hash: String,
    val previousHash: String?,
)
