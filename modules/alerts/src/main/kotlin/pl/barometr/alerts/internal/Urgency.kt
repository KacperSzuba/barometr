package pl.barometr.alerts.internal

/**
 * How much it matters that this arrives now.
 *
 * Chosen by the person on the rule, not computed. Nothing here scores significance yet,
 * and a level filled in by a model that does not exist would read as a guarantee. What
 * it decides today is one thing — whether the quiet hours apply — and "wake me for this
 * one" is a sentence somebody can mean about a profile without any model being right.
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
