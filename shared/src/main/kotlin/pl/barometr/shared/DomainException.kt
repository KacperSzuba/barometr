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
}

abstract class DomainException(
    val kind: ErrorKind,
    /** Stable, machine-readable code such as `invalid_credentials`. Part of the API contract. */
    val code: String,
) : RuntimeException(code)
