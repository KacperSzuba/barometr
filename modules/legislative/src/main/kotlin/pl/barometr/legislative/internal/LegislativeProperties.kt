package pl.barometr.legislative.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Where the line falls between a match this system makes on its own, one it asks a
 * person about, and one not worth anyone's attention.
 *
 * Configuration rather than constants because both numbers are provisional: they were
 * chosen to be cautious, and the only way to set them properly is to watch the review
 * queue for a few weeks. Moving them must not need a release.
 */
@ConfigurationProperties(prefix = "app.legislative")
data class LegislativeProperties(
    /**
     * Title similarity at or above which a print is pinned to an act unasked.
     *
     * Deliberately above what a correct match usually scores. One measured pair — a
     * government bill and the act it became — sits at 0.56, so with this default that
     * match goes to a person rather than being taken automatically. That is the
     * intended direction of error while there is no evidence to calibrate on: a wrong
     * link shows a user the wrong law with nothing to warn them, and the exact link
     * the register states covers the overwhelming majority of documents anyway.
     */
    val automaticMatchAbove: Double = 0.60,

    /**
     * Below this, no candidate is recorded at all. Most prints have no act yet — the
     * bill has not passed — and queueing them for review would bury the queue in
     * questions nobody can answer. Unrelated titles measure around 0.05.
     *
     * Kept above Postgres's own `pg_trgm.similarity_threshold` of 0.3, which the
     * index-narrowing operator applies before this number is ever compared: set lower,
     * this would not be the floor that governs.
     */
    val reviewMatchAbove: Double = 0.35,
) {
    init {
        require(reviewMatchAbove <= automaticMatchAbove) {
            "The review floor must not sit above the automatic threshold: " +
                "$reviewMatchAbove > $automaticMatchAbove"
        }
    }
}
