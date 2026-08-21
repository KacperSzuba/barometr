package pl.barometr.legislative.internal

/**
 * The authorities an act can be addressed by, matching the `CHECK` on
 * `act_identifier.scheme`.
 *
 * The value stored under [SEJM_PRINT] is the address the archive uses for that print
 * — see [SejmPrintAddress] — so resolving a print to its act is an equality rather
 * than a parse.
 */
enum class IdentifierScheme(val wireName: String) {
    ELI("eli"),
    SEJM_PRINT("druk_sejmowy"),
    RCL("rcl_id"),
    JOURNAL_POSITION("dziennik_pozycja"),
}
