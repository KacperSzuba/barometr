package pl.barometr.identity.internal.user

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/** Spring Data behind [Users]; nothing outside `internal.user` names it. */
interface UserRepository : JpaRepository<UserEntity, UUID> {

    fun findByEmail(email: String): UserEntity?

    fun existsByEmail(email: String): Boolean
}
