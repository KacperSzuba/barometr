package pl.barometr.shared

/**
 * A failure a caller caused, carrying how it should be surfaced and a code the caller
 * can act on.
 *
 * The kind lives in [ErrorKind], in a file of its own: the two are read together but
 * changed apart, and the enum is what the application maps to status codes.
 */
abstract class DomainException(
    val kind: ErrorKind,
    /** Stable, machine-readable code such as `invalid_credentials`. Part of the API contract. */
    val code: String,
) : RuntimeException(code)
