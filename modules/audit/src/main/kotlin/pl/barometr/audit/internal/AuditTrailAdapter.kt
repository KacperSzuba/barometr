package pl.barometr.audit.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.barometr.audit.api.AuditTrail
import pl.barometr.audit.api.AuditableAttempt

/**
 * The published port, over the table.
 *
 * **A failure to record is logged and swallowed**, which is the opposite of what this
 * codebase does everywhere else, so it is worth saying why. Everything else here throws
 * rather than lose data. But this is called from a filter wrapped around every request,
 * and a database hiccup that turned into a 500 would take the whole API down to protect
 * a log — while the caller, whose request had already succeeded or been denied on its
 * own merits, would be told something untrue about their own request.
 *
 * The trade is deliberate and its cost is real: a gap in the trail is possible and
 * would be invisible in the trail itself. What makes it survivable is that the gap is
 * loud somewhere else — an error in the log with the attempt in it — and that the chain
 * is intact around it, so nothing that *was* recorded is in doubt.
 */
@Component
class AuditTrailAdapter(private val events: AuditEventRepository) : AuditTrail {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun record(attempt: AuditableAttempt) {
        try {
            events.append(attempt)
        } catch (failure: Exception) {
            log.error(
                "Failed to record an audit entry: {} {} by {} ended {}",
                attempt.action,
                attempt.resource,
                attempt.actor ?: attempt.actorLabel ?: "anonymous",
                attempt.outcome.wireName,
                failure,
            )
        }
    }
}
