package pl.barometr.legislative.internal

/**
 * What one act does to another.
 *
 * Four relations, matching the `CHECK` on `act_reference.relation` — the closed set
 * is the database's, and this type is how the application cannot disagree with it.
 */
enum class ActRelation(val wireName: String) {
    AMENDS("amends"),
    REPEALS("repeals"),

    /** A uniform text: one act restating another in its current wording. */
    CONSOLIDATES("consolidates"),

    /** Issued under another act's authority — a regulation to its statute. */
    IMPLEMENTS("implements"),
    ;

    companion object {
        fun of(wireName: String): ActRelation? = entries.firstOrNull { it.wireName == wireName }
    }
}
