package pl.barometr.alerts.internal

/**
 * How much one item matters to one person, and what made it so.
 *
 * [score] is out of a hundred and is frozen at the moment of the decision. It has to
 * be: a list ordered by a number that moves under it would reshuffle every time it was
 * opened, and a notification from last Tuesday would be ranked by a draft's position
 * this Thursday.
 */
data class Significance(val score: Int, val reasons: List<SignificanceReason>) {
    init {
        require(score in 0..MAXIMUM) { "A significance is a number out of $MAXIMUM, got $score" }
    }

    companion object {
        const val MAXIMUM = 100
    }
}
