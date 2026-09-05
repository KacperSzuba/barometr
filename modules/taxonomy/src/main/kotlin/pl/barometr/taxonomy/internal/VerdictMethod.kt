package pl.barometr.taxonomy.internal

/**
 * Who decided, matching the `CHECK` on `item_industry.method`.
 *
 * The distinction is not bookkeeping: a person's verdict is accepted the moment it is
 * made, and a model's has to clear a threshold first. The database holds both rules.
 */
enum class VerdictMethod(val wireName: String) {
    MANUAL("manual"),
    MODEL("model"),
    ;

    companion object {
        fun of(wireName: String): VerdictMethod? = entries.firstOrNull { it.wireName == wireName }
    }
}
