package pl.barometr.identity.internal.twofactor

import java.time.Instant
import java.util.UUID

/**
 * Somebody's shared secret, as the application holds it: in the clear, in memory, for as
 * long as it takes to check one code.
 *
 * [confirmedAt] is what turns a set-up into a second factor. Until the first correct code
 * arrives, this is somebody who has scanned a QR image and may or may not have an
 * authenticator that works — and their sign-in is unchanged.
 */
data class EnrolledSecret(
    val userId: UUID,
    val secret: String,
    val confirmedAt: Instant?,
    val createdAt: Instant,
) {
    val isConfirmed: Boolean get() = confirmedAt != null
}
