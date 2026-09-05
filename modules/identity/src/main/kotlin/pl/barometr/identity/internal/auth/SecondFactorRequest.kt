package pl.barometr.identity.internal.auth

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * The second half of a sign-in: which challenge, and the code answering it.
 *
 * One field for both kinds of code. An authenticator's six digits and a recovery code
 * arrive the same way and are told apart by what they are, not by which box they were
 * typed into — a caller that had to say which it was would be telling us something the
 * answer must not depend on.
 */
data class SecondFactorRequest(
    val challengeId: UUID,
    @field:NotBlank
    @field:Size(max = MAX_CODE_LENGTH)
    val code: String,
    /**
     * "Do not ask me on this device for a month." Off unless asked for: it is a
     * deliberate weakening of the factor the caller just answered, and a default nobody
     * chose is not a choice.
     */
    val rememberDevice: Boolean = false,
) {
    private companion object {
        /** A recovery code with its separators is nineteen characters; nothing valid is longer. */
        const val MAX_CODE_LENGTH = 32
    }
}
