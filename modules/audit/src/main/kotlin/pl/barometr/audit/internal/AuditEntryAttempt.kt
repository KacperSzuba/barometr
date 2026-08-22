package pl.barometr.audit.internal

import pl.barometr.audit.api.AuditableAttempt

/**
 * A recorded entry read back as the attempt it was.
 *
 * Only for re-hashing: verification has to feed the same fields, in the same shape,
 * that the append hashed — and doing that by hand at the call site is how a
 * verification comes to check something subtly different from what was written.
 */
fun AuditEntry.asAttempt(): AuditableAttempt = AuditableAttempt(
    actor = actor,
    actorLabel = actorLabel,
    action = action,
    resource = resource,
    outcome = outcome,
    status = status,
    peer = peer,
)
