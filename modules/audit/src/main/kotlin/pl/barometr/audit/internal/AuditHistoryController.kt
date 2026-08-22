package pl.barometr.audit.internal

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.barometr.identity.api.callerOf
import java.security.Principal

/**
 * What this account did, and what it was refused.
 *
 * Their own history and nobody else's: the path says `me` rather than taking an
 * identifier, so there is no parameter here that could be pointed at another account.
 * Reading the whole trail is a different question with a different answer — see
 * [ChainIntegrityController] — and this is not the place it gets asked.
 *
 * The CSV exists because the answer to "what did you record about me" has to be
 * something a person can keep, open and hand to somebody else. A JSON array is an
 * answer to a program.
 */
@RestController
@RequestMapping("/api/v1/audit")
class AuditHistoryController(private val events: AuditEventRepository) {

    @GetMapping("/me")
    fun myHistory(caller: Principal, @RequestParam(required = false) limit: Int?): List<EntryResponse> =
        historyFor(caller, limit).map(::describe)

    @GetMapping("/me.csv", produces = ["text/csv"])
    fun myHistoryAsCsv(caller: Principal, @RequestParam(required = false) limit: Int?): String =
        buildString {
            appendLine(HEADER)
            historyFor(caller, limit).forEach { entry ->
                appendLine(
                    listOf(
                        entry.at.toString(),
                        entry.action,
                        entry.resource,
                        entry.outcome.wireName,
                        entry.status?.toString().orEmpty(),
                    ).joinToString(",", transform = ::quoted),
                )
            }
        }

    private fun historyFor(caller: Principal, limit: Int?) =
        events.historyOf(callerOf(caller), boundedTo(limit))

    private fun describe(entry: AuditEntry) = EntryResponse(
        at = entry.at.toString(),
        action = entry.action,
        resource = entry.resource,
        outcome = entry.outcome.wireName,
        status = entry.status,
        // The hash is theirs to keep: somebody who wrote down what we told them can
        // come back later and show it still matches. A trail is only evidence if the
        // person it is about can check it.
        hash = entry.hash,
    )

    /**
     * A field is wrapped in quotes and its own quotes doubled — the whole of RFC 4180
     * that matters here. A resource path can hold a comma, and a spreadsheet reading
     * one row as two is a export that quietly lies.
     */
    private fun quoted(field: String): String = "\"${field.replace("\"", "\"\"")}\""

    private fun boundedTo(limit: Int?): Int = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    data class EntryResponse(
        val at: String,
        val action: String,
        val resource: String,
        val outcome: String,
        val status: Int?,
        val hash: String,
    )

    private companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 1000
        const val HEADER = "at,action,resource,outcome,status"
    }
}
