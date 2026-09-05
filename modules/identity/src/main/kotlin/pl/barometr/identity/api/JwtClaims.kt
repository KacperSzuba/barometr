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

    /**
     * Which signed-in session minted this token — the refresh-token family, which is
     * what the account's device list calls a session.
     *
     * Published for the same reason the others are: the session list has to be able to
     * say "this device, the one you are reading on", and the only thing that knows
     * which session a request belongs to is the token it arrived with. `sid` is the
     * name OpenID Connect gives it, so a client library that already understands the
     * claim understands ours.
     */
    const val SESSION = "sid"

    /**
     * True while a workspace this account belongs to insists on a second factor that the
     * account has not set up yet.
     *
     * Published because the application's filter chain is what acts on it: such a caller
     * is signed in and may reach the enrolment routes and nothing else. Identity cannot
     * enforce that — only the application knows every context's routes — and the claim is
     * how the decision travels between the two. It is recomputed on every refresh, so it
     * clears within one token's lifetime of somebody enrolling.
     */
    const val ENROLMENT_REQUIRED = "enrol"
}
