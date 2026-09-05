package pl.barometr.taxonomy.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * How sure a classifier has to be before its verdict routes anything, bound from the
 * `app.taxonomy` block.
 *
 * A property rather than a constant because it is the dial between two failures, and
 * which one hurts more is something only real output tells you: too low and a
 * construction company hears about fisheries, too high and the queue grows faster than
 * anybody can read it.
 */
@ConfigurationProperties("app.taxonomy")
data class ClassificationProperties(
    /**
     * Below this, a model's verdict waits for a person instead of routing alerts.
     * Two thirds: the specification asks for a macro-F1 above 0.75, and a threshold
     * under that would be routing on guesses the model itself is not making.
     */
    val acceptanceThreshold: Double = 0.66,
    /** How many pending verdicts one page of the review queue holds. */
    val reviewPageSize: Int = 50,

    /**
     * Below this a verdict is not recorded at all, not even to be reviewed.
     *
     * The other end of the dial from [acceptanceThreshold], and it exists for the same
     * reason the review queue does — to be usable. A lone weak stem matching one word
     * of a title is not a question worth putting to a person; recording it would fill
     * the queue with the classifier's least considered opinions and bury the ones it
     * nearly got right.
     */
    val floorConfidence: Double = 0.3,

    /**
     * How many archived subjects one backlog run reads. A bound rather than "until
     * done": the archive is a hundred thousand acts, and a run that read them all would
     * hold the lock for an hour and delay everything else this deployment schedules.
     */
    val subjectsPerSweep: Int = 2_000,
) {
    init {
        require(acceptanceThreshold in 0.0..1.0) { "The threshold is a fraction, got $acceptanceThreshold" }
        require(reviewPageSize in 1..500) { "A review page holds between 1 and 500, got $reviewPageSize" }
        require(floorConfidence in 0.0..acceptanceThreshold) {
            "Nothing can be recorded above the bar it has to clear to route: " +
                "$floorConfidence > $acceptanceThreshold"
        }
        require(subjectsPerSweep in 1..100_000) { "A sweep reads between 1 and 100 000, got $subjectsPerSweep" }
    }
}
