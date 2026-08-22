package pl.barometr.audit.internal

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Whether the trail still adds up.
 *
 * An operator's question, and only theirs: the answer walks every entry there is,
 * including other people's, and a report saying "broken at 4,812" is a fact about the
 * whole log rather than about anybody's own history.
 *
 * It exists because a hash chain nobody checks is decoration. This is the endpoint that
 * turns it into something somebody can be asked to run — after a restore, after an
 * incident, or on a schedule somebody sets.
 */
@RestController
@RequestMapping("/api/v1/audit/integrity")
class ChainIntegrityController(private val integrity: ChainIntegrity) {

    /**
     * `from` skips what was verified when it was younger, which on a table that only
     * grows is the difference between a check somebody runs and one they stop running.
     */
    @GetMapping
    @PreAuthorize("hasRole('OPERATOR')")
    fun verify(@RequestParam(required = false) from: Long?): IntegrityResponse =
        integrity.verify(from ?: ChainIntegrity.GENESIS).let {
            IntegrityResponse(
                checked = it.checked,
                intact = it.intact,
                brokenAt = it.brokenAt,
                why = it.why,
            )
        }

    data class IntegrityResponse(
        val checked: Long,
        val intact: Boolean,
        /** The first entry that did not add up, not necessarily the one that was changed. */
        val brokenAt: Long?,
        val why: String?,
    )
}
