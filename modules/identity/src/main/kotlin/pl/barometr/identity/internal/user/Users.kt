package pl.barometr.identity.internal.user

import java.util.UUID

/**
 * What the identity services need from user storage, and nothing else.
 *
 * A narrow port rather than the repository itself. It is what let the move from
 * Spring Data JPA to jOOQ stop at [JooqUsers]: every service above this interface
 * compiled unchanged, and the tests never needed a database to begin with.
 */
interface Users {

    fun byId(id: UUID): User?

    fun byEmail(email: String): User?

    fun existsWithEmail(email: String): Boolean

    fun add(user: User): User
}
