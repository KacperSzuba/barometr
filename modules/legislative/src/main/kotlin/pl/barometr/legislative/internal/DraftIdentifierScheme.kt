package pl.barometr.legislative.internal

/**
 * The authorities a draft is addressed by, matching the `CHECK` on
 * `draft_identifier.scheme`.
 *
 * The value stored under [SEJM_PRINT] is the address the archive uses for that print,
 * so a draft and the document it arrived as resolve to each other by equality rather
 * than by parsing a number out of one and into the other.
 */
enum class DraftIdentifierScheme(val wireName: String) {
    SEJM_PRINT("druk_sejmowy"),

    /** The Council of Ministers' number, as the Sejm's register states it. */
    RCL("rcl_id"),
}
