package pl.barometr.identity.api

/**
 * How fast a caller of the public API may ask, by who they are.
 *
 * **A tier is a rate, not a permission.** All four see the same public data — that is what
 * makes it public — and what differs is how many requests an hour. Keeping those two ideas
 * apart is what stops "press access" from quietly meaning "extra data", which is a promise
 * this product cannot make to one newsroom without making it to all of them.
 *
 * Published because the application's filter is what applies the numbers, and identity is
 * what decides which one a caller is in.
 */
enum class ApiTier(val wireName: String, val requestsPerHour: Int) {
    /** No key at all: enough to try the API from a terminal, not enough to build on. */
    ANONYMOUS("anonymous", 60),

    /** Somebody who registered and made a key. */
    REGISTERED("registered", 600),

    /**
     * A newsroom. Free and self-serve by design: they are a channel rather than a
     * customer, and a rate limit is not the place to negotiate.
     */
    PRESS("press", 3_000),

    /** Somebody with an agreement, whose volume was part of it. */
    PARTNER("partner", 30_000),
    ;

    companion object {
        fun of(wireName: String): ApiTier? = entries.firstOrNull { it.wireName == wireName }
    }
}
