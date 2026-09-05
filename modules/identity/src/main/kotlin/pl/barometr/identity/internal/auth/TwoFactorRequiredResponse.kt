package pl.barometr.identity.internal.auth

import java.util.UUID

/**
 * The password was right and is not enough.
 *
 * Carries nothing but the identifier of the challenge and how long it lives: it must not
 * be usable for anything on its own, which is the whole reason the second factor is
 * worth having.
 */
data class TwoFactorRequiredResponse(
    val challengeId: UUID,
    val expiresIn: Long,
    /** Always true, so a client that only reads fields can branch on something. */
    val twoFactorRequired: Boolean = true,
) : LoginOutcome
