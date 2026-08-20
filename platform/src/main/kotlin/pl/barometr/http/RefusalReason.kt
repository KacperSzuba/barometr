package pl.barometr.http

enum class RefusalReason {
    /** robots.txt disallows this path for our agent. */
    ROBOTS_DISALLOWED,

    /**
     * The publisher reserved rights against text and data mining. Detectable only
     * from the response, so the content is fetched and then discarded unread.
     */
    TDM_RESERVED,
}
