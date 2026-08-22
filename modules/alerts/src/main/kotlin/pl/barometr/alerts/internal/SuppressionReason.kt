package pl.barometr.alerts.internal

/**
 * Why an address hears nothing more, matching the `CHECK` on
 * `suppressed_address.reason`.
 *
 * Three sentences that mean the same thing to a mail server and different things to
 * support, which is why they are kept apart: an address that never existed is a data
 * problem, and a person who pressed unsubscribe is not.
 */
enum class SuppressionReason(val wireName: String) {
    /** The address does not accept mail, and saying so again would cost reputation. */
    BOUNCED("bounced"),

    /** Marked as spam. The worst thing to ignore, and the fastest to act on. */
    COMPLAINED("complained"),

    /** They asked. */
    UNSUBSCRIBED("unsubscribed"),
    ;

    companion object {
        fun of(wireName: String): SuppressionReason? = entries.firstOrNull { it.wireName == wireName }
    }
}
