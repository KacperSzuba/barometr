package pl.barometr.alerts.internal

/**
 * Why something was ranked where it was, in the words a reader gets shown.
 *
 * Only the legislative half is here, and that is deliberate. What caught an item is
 * already on the notification as the matched interest — "you watch this act", "the
 * word *energia*" — and repeating it as a reason would state one fact in two places
 * and give the two somewhere to disagree. What the matched interest cannot say is why
 * a matter is worth attention *regardless* of who is watching it, and that is what
 * these are.
 *
 * Codes rather than sentences, because the sentence is the frontend's to write and its
 * language is not decided here.
 */
enum class SignificanceReason(val wireName: String) {

    /** Published. There is no further along the path to be. */
    IN_FORCE("in_force"),

    /** Late on the path: closer to being law than most of what is in flight. */
    NEARING_ENACTMENT("nearing_enactment"),

    /** A date somebody else fixed, inside a week. */
    DEADLINE_IMMINENT("deadline_imminent"),

    /** A date somebody else fixed, inside a month. */
    DEADLINE_APPROACHING("deadline_approaching"),

    /**
     * A date somebody else fixed, further out. Still worth something: a vacatio legis
     * of three months is exactly the window somebody needs in order to be ready for
     * it, and hearing about it on the last day is hearing about it too late.
     */
    DEADLINE_AHEAD("deadline_ahead"),
    ;

    companion object {
        fun of(wireName: String): SignificanceReason? = entries.firstOrNull { it.wireName == wireName }
    }
}
