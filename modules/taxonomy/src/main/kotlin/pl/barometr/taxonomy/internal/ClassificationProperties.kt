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
) {
    init {
        require(acceptanceThreshold in 0.0..1.0) { "The threshold is a fraction, got $acceptanceThreshold" }
        require(reviewPageSize in 1..500) { "A review page holds between 1 and 500, got $reviewPageSize" }
    }
}
