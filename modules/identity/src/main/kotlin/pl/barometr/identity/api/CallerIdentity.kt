package pl.barometr.identity.api

import java.security.Principal
import java.util.UUID

/**
 * Who a verified caller is.
 *
 * The fact stated here is that the authenticated principal's name is the token's
 * subject, and that the subject is a [UserId] this system minted. Every context with an
 * endpoint of its own needs it, and each one working it out for itself is how one of
 * them ends up reading a different claim.
 *
 * Takes `java.security.Principal` rather than a `Jwt` deliberately: the JDK type
 * carries everything this needs, so no context has to put a security library on its
 * compile classpath to read one string. What a caller with an unreadable subject means
 * is left to the caller — it is a credential problem, but only the endpoint knows what
 * it costs.
 */
object CallerIdentity {

    /** Null when the subject is not one of our identifiers, which no token of ours has. */
    fun of(caller: Principal): UserId? =
        runCatching { UserId(UUID.fromString(caller.name)) }.getOrNull()
}
