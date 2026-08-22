package pl.barometr.alerts.internal

/**
 * Local hours during which nothing ordinary is sent.
 *
 * Half-open and allowed to wrap midnight, which is the usual case: 22 to 7 means ten at
 * night until seven in the morning, and reading it as "from hour 22 to hour 7 going
 * forwards" would make it quiet for the working day instead.
 */
data class QuietHours(val from: Int, val to: Int) {
    init {
        require(from in HOURS && to in HOURS) { "not an hour of the day: $from-$to" }
        // Equal bounds would mean either "always quiet" or "never", and nobody typing
        // the same number twice means either.
        require(from != to) { "quiet hours must span something" }
    }

    fun covers(hour: Int): Boolean =
        if (from < to) hour in from until to else hour >= from || hour < to

    private companion object {
        val HOURS = 0..23
    }
}
