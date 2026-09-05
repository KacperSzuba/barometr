package pl.barometr.identity.internal.twofactor

/**
 * What somebody needs to set up an authenticator: the secret, and the URI a QR image is
 * drawn from.
 *
 * Both are the same secret. The URI is what a camera reads; the string is what somebody
 * types when the camera cannot, which is the case this is worth carrying for.
 */
data class TotpSetup(val secret: String, val setupUri: String)
