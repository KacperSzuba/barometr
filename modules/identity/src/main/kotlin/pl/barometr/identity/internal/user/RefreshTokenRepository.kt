package pl.barometr.identity.internal.user

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/** Spring Data behind [RefreshTokens]; nothing outside `internal.user` names it. */
interface RefreshTokenRepository : JpaRepository<RefreshTokenEntity, UUID> {

    /**
     * Locks the row for the transaction's duration.
     *
     * Without it two concurrent refreshes both read `used_at = null` and both
     * mint a successor. The second caller blocks here instead, then finds the
     * token already used and takes the grace-window path.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshTokenEntity r where r.tokenHash = :hash")
    fun findByTokenHashForUpdate(@Param("hash") hash: String): RefreshTokenEntity?

    /**
     * Written as a statement rather than by mutating the entity and trusting dirty
     * checking, so that what the transaction does is visible where it is called.
     */
    @Modifying(flushAutomatically = true)
    @Query("update RefreshTokenEntity r set r.usedAt = :at where r.id = :id and r.usedAt is null")
    fun markUsed(@Param("id") id: UUID, @Param("at") at: Instant)

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update RefreshTokenEntity r
           set r.revokedAt = :now
         where r.familyId = :familyId
           and r.revokedAt is null
        """,
    )
    fun revokeFamily(@Param("familyId") familyId: UUID, @Param("now") now: Instant): Int
}
