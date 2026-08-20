package pl.barometr.identity.internal.user

import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.UserSnapshot
import java.time.Instant
import java.util.UUID

/**
 * A user as identity holds one.
 *
 * `roles` is a set here and a comma-separated string in the database. That is a
 * schema debt, not a modelling choice — the column cannot be constrained to known
 * roles, indexed, or queried by role — and keeping the encoding inside [JooqUsers]
 * means the day it becomes a table, nothing above the repository changes.
 */
data class User(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val roles: Set<String> = setOf(DEFAULT_ROLE),
    val enabled: Boolean = true,
    val createdAt: Instant,
) {
    /** This never leaves the module; this is what crosses the boundary. */
    fun toSnapshot(): UserSnapshot =
        UserSnapshot(id = UserId(id), email = email, roles = roles, enabled = enabled)

    companion object {
        const val DEFAULT_ROLE = "USER"
    }
}
