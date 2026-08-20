package pl.barometr.identity.internal.user

import java.util.UUID

/**
 * [Users] in a map.
 *
 * Hand-written rather than mocked: it is a dozen lines, it reads as a specification
 * of what storage owes the services, and it cannot silently agree with a signature
 * that changed.
 */
class InMemoryUsers : Users {

    private val byId = mutableMapOf<UUID, UserEntity>()

    override fun byId(id: UUID): UserEntity? = byId[id]

    override fun byEmail(email: String): UserEntity? = byId.values.firstOrNull { it.email == email }

    override fun existsWithEmail(email: String): Boolean = byEmail(email) != null

    override fun add(user: UserEntity): UserEntity {
        byId[user.id] = user
        return user
    }
}
