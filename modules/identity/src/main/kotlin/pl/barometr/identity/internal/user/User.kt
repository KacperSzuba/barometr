package pl.barometr.identity.internal.user

import pl.barometr.identity.api.Role
import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.UserSnapshot
import java.time.Instant
import java.util.UUID

/** A user as identity holds one. */
data class User(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    /** Stored as rows in `identity.user_roles`, one per role. */
    val roles: Set<Role> = setOf(DEFAULT_ROLE),
    val enabled: Boolean = true,
    val createdAt: Instant,
) {
    /** This never leaves the module; this is what crosses the boundary. */
    fun toSnapshot(): UserSnapshot =
        UserSnapshot(id = UserId(id), email = email, roles = roles, enabled = enabled)

    companion object {
        /** What registration grants, and all it grants. */
        val DEFAULT_ROLE = Role.USER
    }
}
