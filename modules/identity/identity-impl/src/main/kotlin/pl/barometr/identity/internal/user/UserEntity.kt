package pl.barometr.identity.internal.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.UserSnapshot
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users", schema = "identity")
class UserEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID,

    @Column(name = "email", nullable = false, length = 320)
    var email: String,

    @Column(name = "password_hash", nullable = false, length = 72)
    var passwordHash: String,

    @Column(name = "roles", nullable = false, length = 255)
    var roles: String = DEFAULT_ROLE,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    /**
     * Computed, so Hibernate ignores it — `@Id` on a field puts the entity in
     * field-access mode, which leaves plain getters unmapped.
     */
    val roleNames: Set<String>
        get() = roles.split(",").map(String::trim).filter(String::isNotEmpty).toSet()

    /** The entity never leaves the module; this is what crosses the boundary. */
    fun toSnapshot(): UserSnapshot =
        UserSnapshot(id = UserId(id), email = email, roles = roleNames, enabled = enabled)

    companion object {
        const val DEFAULT_ROLE = "USER"
    }
}
