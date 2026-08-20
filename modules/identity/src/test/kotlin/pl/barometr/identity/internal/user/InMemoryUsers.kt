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

    private val byId = mutableMapOf<UUID, User>()

    override fun byId(id: UUID): User? = byId[id]

    override fun byEmail(email: String): User? = byId.values.firstOrNull { it.email == email }

    override fun existsWithEmail(email: String): Boolean = byEmail(email) != null

    override fun add(user: User): User {
        byId[user.id] = user
        return user
    }

    /**
     * Switches an account off.
     *
     * Test-only: the [Users] port has no update method because nothing in identity
     * updates a user yet, and adding one to the contract so that a test can disable
     * an account would be the test dictating the design.
     */
    fun disable(email: String) {
        val user = byEmail(email) ?: error("No user with e-mail '$email'")
        byId[user.id] = user.copy(enabled = false)
    }
}
