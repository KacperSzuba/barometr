package pl.barometr.corpus.internal.diff

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * What the comparison is allowed to believe and how much of it it is allowed to say,
 * bound from the `app.corpus.diff` block.
 *
 * A properties class rather than constants in the algorithm, because both numbers are
 * policy: the first decides when two paragraphs are "the same paragraph, redrafted"
 * and the second how much detail a rewritten annex is worth. Neither is a fact about
 * Polish legal drafting, and both are the sort of thing a deployment tunes after
 * looking at real output.
 */
@ConfigurationProperties(prefix = "app.corpus.diff")
data class DiffProperties(
    /**
     * How alike two units must read before alignment will call them the same unit
     * renumbered. Half: they share most of their three-word runs. Lower, and an
     * unrelated paragraph of boilerplate gets matched to another one — which is worse
     * than an honest "added" and "removed" pair, because it presents a rewrite that
     * never happened.
     */
    val matchFloor: Double = 0.5,
    /**
     * How many word-level runs a single unit's change may list before the unit is
     * reported as rewritten whole. Two hundred: past that nobody is reading
     * highlights, they are reading a new paragraph.
     */
    val maxWordChanges: Int = 200,
) {
    init {
        require(matchFloor in 0.0..1.0) { "The match floor is a fraction, got $matchFloor" }
        require(maxWordChanges > 0) { "A modified unit lists at least one change, got $maxWordChanges" }
    }
}
