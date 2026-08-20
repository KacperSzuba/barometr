package pl.barometr.identity.internal.user

import java.util.UUID

/**
 * What the identity services need from user storage, and nothing else.
 *
 * A narrow port rather than the Spring Data repository itself. Two reasons, and the
 * second is the one that matters: a service that depends on `JpaRepository` cannot
 * be tested without a persistence context, and the module's own storage decision —
 * JPA today, jOOQ once the rest of the system's model reaches it — becomes visible
 * to everything that reads a user.
 */
interface Users {

    fun byId(id: UUID): UserEntity?

    fun byEmail(email: String): UserEntity?

    fun existsWithEmail(email: String): Boolean

    fun add(user: UserEntity): UserEntity
}
