package pl.barometr.identity.internal.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A refresh token as stored — never the token itself, only its SHA-256.
 *
 * The owner is a bare `userId` rather than a `@ManyToOne`. A lazy association
 * would need the class opened for proxying, and nothing here walks the object
 * graph: rotation looks the user up by id exactly once. The foreign key still
 * exists in the schema.
 */
@Entity
@Table(name = "refresh_tokens", schema = "identity")
class RefreshTokenEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "token_hash", nullable = false, length = 64)
    var tokenHash: String,

    /** Shared by every token descending from one login; revoked as a unit on replay. */
    @Column(name = "family_id", nullable = false)
    var familyId: UUID,

    /** The token this one replaced. Lineage for audit; a token may have several
     * successors when parallel refreshes land inside the grace window. */
    @Column(name = "predecessor_id")
    var predecessorId: UUID? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "used_at")
    var usedAt: Instant? = null,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
