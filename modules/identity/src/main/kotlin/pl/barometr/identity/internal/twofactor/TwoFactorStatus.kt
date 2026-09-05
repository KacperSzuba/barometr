package pl.barometr.identity.internal.twofactor

/**
 * Where an account stands on second factors, for the settings screen.
 *
 * [enrolmentStarted] without [enabled] is somebody who scanned a QR image and never
 * confirmed — a state worth showing, because from the outside it looks exactly like
 * having done nothing.
 */
data class TwoFactorStatus(
    val enabled: Boolean,
    val enrolmentStarted: Boolean,
    val recoveryCodesLeft: Int,
)
