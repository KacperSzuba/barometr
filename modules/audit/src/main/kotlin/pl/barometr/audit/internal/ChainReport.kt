package pl.barometr.audit.internal

/**
 * What a walk of the chain found.
 *
 * [brokenAt] is the first entry that did not add up, not the one that was tampered
 * with: an entry changed in place breaks itself, and an entry removed breaks the one
 * after it. Which of the two it was is in [why].
 */
data class ChainReport(
    val checked: Long,
    val intact: Boolean,
    val brokenAt: Long? = null,
    val why: String? = null,
)
