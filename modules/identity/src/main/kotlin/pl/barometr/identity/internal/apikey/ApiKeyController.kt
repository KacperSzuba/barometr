package pl.barometr.identity.internal.apikey

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.barometr.identity.api.ApiScope
import pl.barometr.identity.api.callerOf
import java.security.Principal
import java.util.UUID

/**
 * The account's own keys for the public API.
 *
 * The key itself comes back exactly once, from the call that makes it: it is stored as a
 * hash, so there is nowhere to fetch it from afterwards, and a route that could show it
 * again would be a route worth stealing an account for.
 */
@RestController
@RequestMapping("/api/v1/me/api-keys")
class ApiKeyController(private val keys: PublicApiKeys) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun issue(caller: Principal, @Valid @RequestBody request: IssueRequest): MintedResponse {
        val scopes = request.scopes.map { ApiScope.of(it) ?: throw UnknownApiScopeException(it) }.toSet()
        val minted = keys.issueKey(callerOf(caller), request.name, scopes)

        return MintedResponse(describe(minted.key), minted.secret)
    }

    @GetMapping
    fun list(caller: Principal): List<KeyResponse> = keys.keysOf(callerOf(caller)).map(::describe)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(caller: Principal, @PathVariable id: UUID) {
        keys.revokeKey(callerOf(caller), id)
    }

    private fun describe(key: IssuedApiKey) = KeyResponse(
        id = key.id,
        name = key.name,
        tier = key.tier.wireName,
        requestsPerHour = key.tier.requestsPerHour,
        scopes = key.scopes.map { it.wireName }.sorted(),
        createdAt = key.createdAt.toString(),
        expiresAt = key.expiresAt?.toString(),
        revokedAt = key.revokedAt?.toString(),
        lastUsedAt = key.lastUsedAt?.toString(),
        requests = key.requests,
    )

    data class IssueRequest(
        @field:NotBlank
        @field:Size(max = 120)
        val name: String,
        @field:Size(min = 1, max = 2)
        val scopes: List<String> = listOf(ApiScope.READ.wireName),
    )

    data class KeyResponse(
        val id: UUID,
        val name: String,
        val tier: String,
        /** The rate this tier allows, so a client can pace itself without discovering it by being refused. */
        val requestsPerHour: Int,
        val scopes: List<String>,
        val createdAt: String,
        val expiresAt: String?,
        val revokedAt: String?,
        val lastUsedAt: String?,
        /** How many public requests this key has made. */
        val requests: Long,
    )

    /** The one response that carries the key. There is no second one. */
    data class MintedResponse(val key: KeyResponse, val secret: String)
}
