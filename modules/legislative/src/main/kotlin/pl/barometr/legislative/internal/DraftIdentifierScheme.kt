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

    /**
     * The Council of Ministers' number — `RM-0610-102-23` — which is what the Sejm's
     * register prints when it points back at RPL, and what RPL's own resolver takes.
     */
    COUNCIL_OF_MINISTERS("rcl_rm"),

    /** RPL's project id, which is what its URLs are built from and the archive keys on. */
    RCL_PROJECT("rcl_projekt"),

    /**
     * The ministry's number in its programme of work — `UD383` — which is what a
     * person quoting the draft actually says.
     */
    PROGRAMME_OF_WORK("wykaz_prac"),
}
