package pl.barometr.identity.api

/**
 * The only view other modules get of a user.
 *
 * Deliberately not the persistence entity: exposing that would let any module
 * depend on identity's storage shape, and changing a column would ripple across
 * the system.
 */
data class UserSnapshot(
    val id: UserId,
    val email: String,
    val roles: Set<String>,
    val enabled: Boolean,
)

/**
 * Read port for modules that need to resolve a user — alerts addressing a
 * recipient, audit recording an actor. Implemented inside identity.
 */
interface UserLookup {
    fun findById(id: UserId): UserSnapshot?

    fun findByEmail(email: String): UserSnapshot?
}
