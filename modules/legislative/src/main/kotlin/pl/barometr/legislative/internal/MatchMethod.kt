package pl.barometr.legislative.internal

/**
 * How an identifier came to point at an act, matching the `CHECK` on
 * `act_identifier.resolved_by`.
 *
 * Kept beside the confidence rather than folded into it: a similarity of 1.0 arrived
 * at by comparing titles is not the same claim as an identifier the publisher printed
 * on the act itself, and a reviewer needs to see which one they are looking at.
 */
enum class MatchMethod(val wireName: String) {
    /** The source stated it: an ELI, or a print number printed on the act. */
    EXACT("exact"),

    /** Titles were compared. Always carries a similarity. */
    FUZZY("fuzzy"),

    /** A person decided, through the review queue. */
    MANUAL("manual"),
}
