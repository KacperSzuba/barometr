package pl.barometr.identity.api

/**
 * Turns a presented key into what it is worth, or into nothing.
 *
 * Published because the caller is the application's filter chain — only the application
 * knows which routes are the public ones — while what a key means belongs to identity,
 * beside every other credential this system issues.
 *
 * A key that is unknown, revoked or expired is nothing, and all three are the same
 * answer: telling a caller which of the three their key is would be telling them
 * something about keys that exist.
 */
interface ApiKeys {

    fun grantFor(presentedKey: String): ApiKeyGrant?
}
