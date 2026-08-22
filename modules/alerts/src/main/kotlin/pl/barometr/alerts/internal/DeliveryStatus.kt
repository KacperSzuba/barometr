package pl.barometr.alerts.internal

/**
 * What became of one digest's mail, matching the `CHECK` on `email_delivery.status`.
 */
enum class DeliveryStatus(val wireName: String) {
    SENT("sent"),

    /** The transport refused or failed. Retried, because most of those are temporary. */
    FAILED("failed"),

    /**
     * Nothing was sent and nothing will be: the address is on the suppression list.
     * Recorded rather than skipped, so a digest that will never go out stops looking
     * like one that has not gone out yet.
     */
    SUPPRESSED("suppressed"),
    ;

    companion object {
        fun of(wireName: String): DeliveryStatus? = entries.firstOrNull { it.wireName == wireName }
    }
}
