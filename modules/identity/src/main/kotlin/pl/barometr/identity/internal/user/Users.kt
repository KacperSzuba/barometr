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

    /**
     * Several users at once, for a list that has to name them.
     *
     * Here rather than left to the caller looping over [byId], because the caller that
     * loops is a members endpoint and a workspace may hold as many seats as somebody
     * bought — one query per row is a page that gets slower the more it has to show.
     * Users that do not exist are absent from the result rather than null in it.
     */
    fun allById(ids: Set<UUID>): List<User>

    fun byEmail(email: String): User?

    fun existsWithEmail(email: String): Boolean

    fun add(user: User): User
}
