package pl.barometr.identity.api

import java.util.UUID

/**
 * What a presented key turns out to be worth.
 *
 * Carries no secret: the key is hashed on the way in, and what comes back is who it is,
 * how fast they may ask and what they may reach. The application's filter needs exactly
 * these three things and nothing else about the account.
 */
data class ApiKeyGrant(
    val keyId: UUID,
    val owner: UserId,
    val tier: ApiTier,
    val scopes: Set<ApiScope>,
) {
    fun permits(scope: ApiScope): Boolean = scope in scopes
}
