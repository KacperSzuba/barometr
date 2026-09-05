package pl.barometr.taxonomy.internal

/**
 * What has become of one verdict, matching the `CHECK` on `item_industry.status`.
 *
 * [PENDING] is the queue the confidence threshold fills: a guess nobody has looked at
 * yet, which routes nothing and is not lost either.
 */
enum class VerdictStatus(val wireName: String) {
    ACCEPTED("accepted"),
    PENDING("pending"),
    REJECTED("rejected"),
    ;

    companion object {
        fun of(wireName: String): VerdictStatus? = entries.firstOrNull { it.wireName == wireName }
    }
}
