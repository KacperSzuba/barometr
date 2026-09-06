package pl.barometr.taxonomy.internal

/**
 * One verdict waiting for a decision, with the one thing needed to make it.
 *
 * The title is not part of the verdict and is fetched beside it on purpose: taxonomy
 * does not hold what an act is called, legislative does, and copying the title into
 * `item_industry` would be a second place for it to be wrong the day a register
 * corrects one.
 */
data class PendingClassification(
    val verdict: IndustryVerdict,
    /** Null for a subject the catalogue no longer holds, which the queue still shows. */
    val title: String?,
)
