package pl.barometr.identity.internal.auth

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.internal.config.JwtProperties
import pl.barometr.identity.internal.user.RefreshToken
import pl.barometr.identity.internal.user.RefreshTokens
import pl.barometr.shared.Ids
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class IssuedRefreshToken(
    val raw: String,
    val id: UUID,
    val familyId: UUID,
    val expiresAt: Instant,
)

data class RotationResult(val userId: UUID, val refreshToken: IssuedRefreshToken)

@Service
class RefreshTokenService(
    private val tokens: RefreshTokens,
    private val properties: JwtProperties,
    private val clock: Clock,
) {

    private val secureRandom = SecureRandom()

    fun issue(
        userId: UUID,
        familyId: UUID = Ids.next(),
        predecessorId: UUID? = null,
    ): IssuedRefreshToken {
        val raw = generateRawToken()
        val now = clock.instant()
        val entity = RefreshToken(
            id = Ids.next(),
            userId = userId,
            tokenHash = sha256Hex(raw),
            familyId = familyId,
            predecessorId = predecessorId,
            expiresAt = now.plus(properties.refreshTtl),
            createdAt = now,
        )
        tokens.add(entity)
        return IssuedRefreshToken(raw, entity.id, familyId, entity.expiresAt)
    }

    /**
     * Rotation: the presented token is retired and a fresh one takes its place.
     *
     * The grace window is what stops a normal parallel refresh from looking like
     * theft. Next.js runs its route guard per request and fires several at once, so
     * two requests routinely present the same token; the second finds `used_at`
     * already set, which is indistinguishable from a replay except by how long ago
     * the first one happened.
     *
     * Inside the window the second caller is issued a *fresh* token in the same
     * family rather than the successor the first caller received. It has to be:
     * only the SHA-256 of a token is ever stored, so the existing successor cannot
     * be reproduced in a form anyone could send. Two live tokens in one family is
     * not a weakening — the family is revoked as a unit, and both expire together.
     *
     * Serialisation is the row lock taken by [RefreshTokens.byTokenHashForUpdate],
     * so this holds however many instances are running. The previous implementation
     * kept raw successors in a process-local map and, by its own admission, worked
     * on one instance only.
     *
     * `noRollbackFor` matters more than it looks. The theft branch revokes the
     * whole family and then throws — a plain `@Transactional` would roll that
     * revocation straight back, leaving the stolen family alive. The exception
     * still propagates; only the rollback is suppressed.
     */
    @Transactional(noRollbackFor = [RefreshTokenReuseException::class])
    fun rotate(rawToken: String): RotationResult {
        val now = clock.instant()

        val stored = tokens.byTokenHashForUpdate(sha256Hex(rawToken))
            ?: throw InvalidRefreshTokenException()

        if (stored.revokedAt != null) {
            tokens.revokeFamily(stored.familyId, now)
            throw RefreshTokenReuseException()
        }
        if (stored.expiresAt.isBefore(now)) {
            throw InvalidRefreshTokenException()
        }

        val usedAt = stored.usedAt
        if (usedAt != null && Duration.between(usedAt, now) > properties.refreshGrace) {
            tokens.revokeFamily(stored.familyId, now)
            throw RefreshTokenReuseException()
        }

        // Only the first use marks the token spent: `used_at` is when the window
        // opened, not when the latest caller arrived, or a token presented every
        // second would keep its own window open indefinitely.
        if (usedAt == null) tokens.markUsed(stored.id, now)

        return RotationResult(stored.userId, issue(stored.userId, stored.familyId, stored.id))
    }

    /** Logout: kills the whole family, so every descendant of that login dies too. */
    @Transactional
    fun revokeFamilyOf(rawToken: String): UUID? {
        val stored = tokens.byTokenHashForUpdate(sha256Hex(rawToken)) ?: return null
        tokens.revokeFamily(stored.familyId, clock.instant())
        return stored.userId
    }

    private fun generateRawToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Only the hash is stored, so a database dump on its own yields no usable
     * refresh tokens. Plain SHA-256 is right here — unlike a password, the input
     * is already 256 bits of entropy and needs no work factor.
     */
    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
