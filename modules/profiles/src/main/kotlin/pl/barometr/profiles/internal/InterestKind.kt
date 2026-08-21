package pl.barometr.profiles.internal

/**
 * What sort of thing a subscriber chose, matching the `CHECK` on
 * `profile_interest.kind`.
 *
 * Closed, because every kind needs a matching rule written for it and a kind nobody
 * has written one for would silently match nothing — which looks exactly like a quiet
 * legislative week.
 */
enum class InterestKind(val wireName: String) {
    /** An industry, by its code. Matches anything classified beneath it. */
    PKD("pkd"),

    /** A place, by TERYT. Matches anything about that place or a place inside it. */
    REGION("region"),

    /** One published act, by its ELI. */
    ACT("act"),

    /** One draft, by the address the archive knows it as. */
    DRAFT("draft"),

    /** A word or phrase, matched against titles the way search matches them. */
    KEYWORD("keyword"),
    ;

    companion object {
        fun of(wireName: String): InterestKind? = entries.firstOrNull { it.wireName == wireName }
    }
}
