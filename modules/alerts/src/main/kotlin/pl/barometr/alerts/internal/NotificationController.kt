package pl.barometr.alerts.internal

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.util.UUID
import kotlin.math.min

/**
 * What I have been told, and why I was not told the rest.
 *
 * The second half is not a debugging aid. "Why did I not get an alert about this" is
 * the question this product will be judged on, and an engine whose answer lives only
 * in a log file cannot be defended to the person asking.
 */
@RestController
@RequestMapping("/api/v1/alerts")
class NotificationController(
    private val notifications: NotificationRepository,
    private val decisions: AlertDecisionRepository,
) {

    @GetMapping
    fun list(caller: Principal, @RequestParam(required = false) limit: Int?): List<AlertResponse> =
        notifications.listFor(readerOf(caller), boundedTo(limit)).map {
            AlertResponse(
                id = it.id,
                subjectKind = it.subjectKind,
                subjectId = it.subjectId,
                title = it.title,
                matchedKind = it.matchedBy.kind.wireName,
                matchedValue = it.matchedBy.value,
                profileId = it.profile.value,
                profileVersion = it.profileVersion,
                createdAt = it.createdAt.toString(),
                readAt = it.readAt?.toString(),
            )
        }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markRead(caller: Principal, @PathVariable id: UUID) {
        // Marking one that was already read, or one that is not the caller's, is not an
        // error worth a status code: both mean "there is nothing to do here".
        notifications.markRead(readerOf(caller), id)
    }

    @GetMapping("/decisions")
    fun decisions(caller: Principal, @RequestParam(required = false) limit: Int?): List<DecisionResponse> =
        decisions.listFor(readerOf(caller), boundedTo(limit)).map {
            DecisionResponse(
                subjectKind = it.subjectKind,
                subjectId = it.subjectId,
                decision = it.decision,
                reason = it.reason,
                decidedAt = it.decidedAt.toString(),
            )
        }

    private fun boundedTo(limit: Int?) = min(limit ?: DEFAULT_LIMIT, MAX_LIMIT).coerceAtLeast(1)

    data class AlertResponse(
        val id: UUID,
        val subjectKind: String,
        val subjectId: String,
        val title: String,
        val matchedKind: String,
        val matchedValue: String,
        val profileId: UUID,
        val profileVersion: Int,
        val createdAt: String,
        val readAt: String?,
    )

    /** One line of the log that answers "why did I not hear about this". */
    data class DecisionResponse(
        val subjectKind: String,
        val subjectId: String,
        val decision: String,
        val reason: String,
        val decidedAt: String,
    )

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}
