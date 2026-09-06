package pl.barometr.audit.api

import pl.barometr.identity.api.UserId

/**
 * One thing somebody tried to do.
 *
 * Deliberately described in the vocabulary of the API rather than of any context: the
 * method and the path, not a name this log invented. An audit trail that needed to
 * understand what it was recording would have to be changed every time anything else
 * was, and the day somebody skipped that change is the day it stopped being complete.
 */
data class AuditableAttempt(
    /** Null for somebody who never got as far as being anybody. */
    val actor: UserId?,
    /** What they presented as, if anything — an e-mail from a failed sign-in. */
    val actorLabel: String? = null,
    /** `POST`, `DELETE`: what was attempted. */
    val action: String,
    /** What it was attempted on, as the API names it. */
    val resource: String,
    val outcome: AuditOutcome,
    val status: Int? = null,
    /**
     * The peer's address. Behind a reverse proxy that is the proxy, and that is the
     * honest answer — a forwarded header is one anybody can set.
     */
    val peer: String? = null,
    /**
     * Why, for the few entries a request does not explain.
     *
     * The system ends every session an account has when a refresh token is replayed or
     * a device goes quiet, and the request that triggered that is indistinguishable
     * from an expired token. Null for everything else, which is nearly all of it: the
     * method and the path already say what happened.
     */
    val detail: String? = null,
)
