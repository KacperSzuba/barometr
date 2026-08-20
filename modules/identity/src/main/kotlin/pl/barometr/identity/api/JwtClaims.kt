package pl.barometr.identity.api

/**
 * The claims identity puts in an access token.
 *
 * Published rather than internal, because a claim name is a contract between two
 * places that must agree: identity mints the token, and the application's filter
 * chain reads it to decide what the caller may do. Both used to spell `"roles"` out
 * separately, which is one fact in two files and one typo away from every request
 * arriving with no authorities at all.
 */
object JwtClaims {
    const val EMAIL = "email"

    /** A list of [Role] names. Absent means the token grants nothing. */
    const val ROLES = "roles"
}
