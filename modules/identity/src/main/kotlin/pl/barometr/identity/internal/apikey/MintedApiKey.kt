package pl.barometr.identity.internal.apikey

/**
 * A key and its record, together for exactly as long as it takes to answer one request.
 *
 * [secret] is never stored and never returned again: what the database holds is its
 * SHA-256, and somebody who loses a key makes another one.
 */
data class MintedApiKey(val key: IssuedApiKey, val secret: String)
