package pl.barometr.shared

/**
 * How a failure should be surfaced, described without reference to HTTP.
 *
 * Translating these into status codes is the application layer's job, which
 * keeps domain modules unaware of the transport that happens to carry them —
 * the same exception is equally meaningful to a scheduled job or a CLI.
 */
enum class ErrorKind {
    UNAUTHENTICATED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    INVALID,

    /**
     * Refused for asking too often, rather than for asking something wrong.
     *
     * Its own kind rather than [FORBIDDEN] because the two say opposite things to a
     * client: forbidden means never, this means not yet, and a client that cannot tell
     * them apart either gives up on a request that would have succeeded or retries one
     * that never will.
     */
    RATE_LIMITED,
}
