package pl.barometr.identity.api

/**
 * Read port for modules that need to resolve a user — alerts addressing a
 * recipient, audit recording an actor. Implemented inside identity.
 */
interface UserLookup {
    fun findById(id: UserId): UserSnapshot?

    fun findByEmail(email: String): UserSnapshot?
}
