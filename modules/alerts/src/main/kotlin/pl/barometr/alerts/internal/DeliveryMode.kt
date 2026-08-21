package pl.barometr.alerts.internal

/**
 * How often somebody wants hearing from us, matching the `CHECK` on
 * `delivery_preference.mode`.
 *
 * Four, and no more, because each one is a window this code has to know how to close.
 * A mode nobody wrote a window for would look like a setting and behave like silence.
 */
enum class DeliveryMode(val wireName: String) {
    /** Every match goes out on the next run. */
    IMMEDIATE("immediate"),

    /** One window an hour, on the hour. */
    HOURLY("hourly"),

    /** One window a day, at a local hour they chose. */
    DAILY("daily"),

    /** One window a week, on a local day and hour they chose. */
    WEEKLY("weekly"),
    ;

    /** Whether this mode needs an hour to know when its window closes. */
    val needsHour: Boolean get() = this == DAILY || this == WEEKLY

    companion object {
        fun of(wireName: String): DeliveryMode? = entries.firstOrNull { it.wireName == wireName }
    }
}
