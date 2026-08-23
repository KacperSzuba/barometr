package pl.barometr.alerts.internal

/**
 * How much it matters that this arrives *now*.
 *
 * Distinct from significance, and the two are easy to confuse. Significance is a
 * judgement this system makes about a matter and uses to order a list; urgency is a
 * sentence somebody wrote about a profile — "wake me for this one" — and it decides
 * one thing only: whether the quiet hours apply.
 *
 * Chosen by a person rather than computed, deliberately. A model deciding at three in
 * the morning that something was worth waking somebody for would be making a promise
 * on their behalf, and no score this system computes is good enough to make it.
 */
enum class Urgency(val wireName: String) {
    NORMAL("normal"),

    /** Goes out through the quiet hours, and out of any window. */
    CRITICAL("critical"),
    ;

    companion object {
        fun of(wireName: String): Urgency? = entries.firstOrNull { it.wireName == wireName }
    }
}
