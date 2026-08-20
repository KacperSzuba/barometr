package pl.barometr.identity.internal.user

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** [RefreshTokens] over Spring Data JPA. */
@Component
class JpaRefreshTokens(private val repository: RefreshTokenRepository) : RefreshTokens {

    override fun byTokenHashForUpdate(hash: String): RefreshTokenEntity? =
        repository.findByTokenHashForUpdate(hash)

    override fun add(token: RefreshTokenEntity): RefreshTokenEntity = repository.save(token)

    override fun markUsed(id: UUID, at: Instant) {
        repository.markUsed(id, at)
    }

    override fun revokeFamily(familyId: UUID, at: Instant): Int =
        repository.revokeFamily(familyId, at)
}
