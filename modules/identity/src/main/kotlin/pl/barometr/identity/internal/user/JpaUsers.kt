package pl.barometr.identity.internal.user

import org.springframework.stereotype.Component
import java.util.UUID

/** [Users] over Spring Data JPA. Optional-to-null happens here, once. */
@Component
class JpaUsers(private val repository: UserRepository) : Users {

    override fun byId(id: UUID): UserEntity? = repository.findById(id).orElse(null)

    override fun byEmail(email: String): UserEntity? = repository.findByEmail(email)

    override fun existsWithEmail(email: String): Boolean = repository.existsByEmail(email)

    override fun add(user: UserEntity): UserEntity = repository.save(user)
}
