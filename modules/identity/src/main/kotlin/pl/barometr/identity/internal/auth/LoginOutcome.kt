package pl.barometr.identity.internal.auth

/**
 * What a password gets you: tokens, or a second question.
 *
 * A sealed pair rather than one response with everything nullable, because the two are
 * not two shapes of the same answer — one is a completed sign-in and the other is a
 * sign-in that has not happened. The endpoint distinguishes them by status code as well,
 * so a client that reads neither the type nor the fields still cannot confuse them.
 */
sealed interface LoginOutcome
