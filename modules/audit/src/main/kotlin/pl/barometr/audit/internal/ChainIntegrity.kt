package pl.barometr.audit.internal

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Walks the chain and reports the first place it stops adding up.
 *
 * A hash chain nobody checks is decoration. This is what turns it into a guarantee
 * somebody can act on: it recomputes every entry's hash from its own fields and its
 * predecessor's, and stops at the first that disagrees.
 *
 * Two ways it can disagree, and they are told apart because they mean different things.
 * An entry whose own hash does not match its fields has had those fields changed. An
 * entry whose recorded predecessor is not the hash of the entry before it means one was
 * removed or inserted between them.
 */
@Service
@Transactional(readOnly = true)
class ChainIntegrity(private val events: AuditEventRepository) {

    /**
     * [from] is where to start, which on a table that only grows is the difference
     * between a question somebody can ask and one they stop asking. Everything before
     * it was verified when it was younger; a slice still catches an edit or a deletion
     * inside itself, because both break a link this walk crosses.
     */
    fun verify(from: Long = GENESIS): ChainReport {
        var checked = 0L
        var after = from
        var previous: String? = null
        var first = true

        while (true) {
            val page = events.inChainOrder(after, PAGE)
            if (page.isEmpty()) return ChainReport(checked, intact = true)

            page.forEach { entry ->
                // The first entry this walk sees is allowed to point at anything: the
                // first ever written points at nothing, and a walk that starts partway
                // begins at one whose predecessor it deliberately did not read.
                if (!first && entry.previousHash != previous) {
                    return ChainReport(checked, intact = false, brokenAt = entry.sequence, why = LINK)
                }
                if (entry.hash != AuditHash.of(entry.previousHash, entry.at, entry.asAttempt())) {
                    return ChainReport(checked, intact = false, brokenAt = entry.sequence, why = CONTENT)
                }

                previous = entry.hash
                checked++
                first = false
            }

            after = page.last().sequence
        }
    }

    companion object {
        /**
         * Walked in pages, because this table only grows and a verification that had to
         * hold all of it in memory would stop being runnable exactly when it mattered.
         */
        const val PAGE = 500

        /** From the beginning, which is what a sequence starting at one makes 0 mean. */
        const val GENESIS = 0L

        const val LINK = "an entry was removed or inserted before this one"
        const val CONTENT = "this entry's own fields were changed"
    }
}
