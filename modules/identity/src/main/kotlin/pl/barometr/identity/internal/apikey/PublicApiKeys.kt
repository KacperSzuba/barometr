package pl.barometr.identity.internal.apikey

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.ApiKeyGrant
import pl.barometr.identity.api.ApiKeys
import pl.barometr.identity.api.ApiScope
import pl.barometr.identity.api.ApiTier
import pl.barometr.identity.api.UserId
import pl.barometr.shared.Ids
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Making, listing and revoking keys for the public API, and turning a presented one into
 * what it is worth.
 *
 * **The plaintext exists once.** It is returned by the call that mints it and stored as a
 * SHA-256, like every other bearer credential here — somebody who loses a key makes
 * another one, which is a smaller inconvenience than a database dump full of working keys.
 *
 * **A key's tier is not the owner's to choose.** Registering gets `registered`; press and
 * partner are granted, because a rate is what somebody else pays for in bandwidth. The
 * self-serve press tier the specification asks for is a verification step this does not
 * have yet, and until it does, handing out the tier on request would be handing it to
 * whoever asks.
 */
@Service
class PublicApiKeys(
    private val keys: ApiKeyRepository,
    private val clock: Clock,
) : ApiKeys {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    /** @return the key itself, which is the only time it exists in a readable form. */
    @Transactional
    fun issueKey(owner: UserId, name: String, scopes: Set<ApiScope>, expiresAt: Instant? = null): MintedApiKey {
        require(scopes.isNotEmpty()) { "A key with no scope can reach nothing" }

        val secret = "$PREFIX${Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(KEY_BYTES).also(random::nextBytes))}"
        val issued = IssuedApiKey(
            id = Ids.next(),
            owner = owner.value,
            name = name.trim(),
            tier = ApiTier.REGISTERED,
            scopes = scopes,
            createdAt = clock.instant(),
            expiresAt = expiresAt,
            revokedAt = null,
            lastUsedAt = null,
            requests = 0,
        )

        keys.issue(issued, hash(secret))
        log.info("API key {} issued to {} with scopes {}", issued.id, owner.value, scopes.map { it.wireName })

        return MintedApiKey(issued, secret)
    }

    @Transactional(readOnly = true)
    fun keysOf(owner: UserId): List<IssuedApiKey> = keys.forOwner(owner.value)

    @Transactional
    fun revokeKey(owner: UserId, id: UUID) {
        if (!keys.revoke(owner.value, id, clock.instant())) throw UnknownApiKeyException(id.toString())
    }

    /**
     * What a presented key is worth, and one request counted against it.
     *
     * The count is written here rather than by the caller because forgetting it is
     * invisible: a key with no usage looks exactly like a key nobody is using.
     */
    @Transactional
    override fun grantFor(presentedKey: String): ApiKeyGrant? {
        val key = keys.liveByHash(hash(presentedKey), clock.instant()) ?: return null
        keys.recordUse(key.id, clock.instant())

        return ApiKeyGrant(key.id, UserId(key.owner), key.tier, key.scopes)
    }

    /**
     * Plain SHA-256, for the reason the refresh tokens give: the input is already
     * thirty-two random bytes chosen by us rather than a password chosen by a person.
     */
    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val KEY_BYTES = 32

        /**
         * A visible prefix, so a key found in a log or a repository is recognisable as one
         * — which is what makes automated secret scanning possible at all.
         */
        const val PREFIX = "brmtr_"
    }
}
