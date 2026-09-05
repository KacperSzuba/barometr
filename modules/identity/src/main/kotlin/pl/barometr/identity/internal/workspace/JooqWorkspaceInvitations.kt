package pl.barometr.identity.internal.workspace

import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import pl.barometr.identity.internal.jooq.tables.references.WORKSPACE_INVITATION
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** [WorkspaceInvitations] over jOOQ. */
@Repository
@Transactional(readOnly = true)
class JooqWorkspaceInvitations(private val dsl: DSLContext) : WorkspaceInvitations {

    @Transactional
    override fun issue(invitation: PendingInvitation): PendingInvitation {
        dsl.insertInto(WORKSPACE_INVITATION)
            .set(WORKSPACE_INVITATION.ID, invitation.id)
            .set(WORKSPACE_INVITATION.WORKSPACE_ID, invitation.workspace.value)
            .set(WORKSPACE_INVITATION.EMAIL, invitation.email)
            .set(WORKSPACE_INVITATION.ROLE, invitation.role.wireName)
            .set(WORKSPACE_INVITATION.TOKEN_HASH, invitation.tokenHash)
            .set(WORKSPACE_INVITATION.INVITED_BY, invitation.invitedBy.value)
            .set(WORKSPACE_INVITATION.CREATED_AT, at(invitation.createdAt))
            .set(WORKSPACE_INVITATION.EXPIRES_AT, at(invitation.expiresAt))
            .execute()

        return invitation
    }

    /**
     * Expiry and settlement are part of the query, not a check afterwards: an invitation
     * that has run out or been taken must not be found at all, so no path above can
     * forget to look.
     */
    override fun byTokenHash(hash: String, now: Instant): PendingInvitation? =
        dsl.selectFrom(WORKSPACE_INVITATION)
            .where(WORKSPACE_INVITATION.TOKEN_HASH.eq(hash))
            .and(WORKSPACE_INVITATION.ACCEPTED_AT.isNull)
            .and(WORKSPACE_INVITATION.REVOKED_AT.isNull)
            .and(WORKSPACE_INVITATION.EXPIRES_AT.gt(at(now)))
            .fetchOne(::toInvitation)

    override fun openIn(workspace: WorkspaceId): List<PendingInvitation> =
        dsl.selectFrom(WORKSPACE_INVITATION)
            .where(WORKSPACE_INVITATION.WORKSPACE_ID.eq(workspace.value))
            .and(WORKSPACE_INVITATION.ACCEPTED_AT.isNull)
            .and(WORKSPACE_INVITATION.REVOKED_AT.isNull)
            .orderBy(WORKSPACE_INVITATION.CREATED_AT)
            .fetch(::toInvitation)

    override fun countOpenIn(workspace: WorkspaceId): Int =
        dsl.fetchCount(
            WORKSPACE_INVITATION,
            WORKSPACE_INVITATION.WORKSPACE_ID.eq(workspace.value)
                .and(WORKSPACE_INVITATION.ACCEPTED_AT.isNull)
                .and(WORKSPACE_INVITATION.REVOKED_AT.isNull),
        )

    /**
     * `WHERE accepted_at IS NULL` is the claim: two people opening the same link at once
     * means one of them takes the seat and the other is told there is nothing to take.
     */
    @Transactional
    override fun accept(id: UUID, at: Instant): Boolean =
        dsl.update(WORKSPACE_INVITATION)
            .set(WORKSPACE_INVITATION.ACCEPTED_AT, at(at))
            .where(WORKSPACE_INVITATION.ID.eq(id))
            .and(WORKSPACE_INVITATION.ACCEPTED_AT.isNull)
            .and(WORKSPACE_INVITATION.REVOKED_AT.isNull)
            .execute() > 0

    @Transactional
    override fun revoke(workspace: WorkspaceId, id: UUID, at: Instant): Boolean =
        dsl.update(WORKSPACE_INVITATION)
            .set(WORKSPACE_INVITATION.REVOKED_AT, at(at))
            .where(WORKSPACE_INVITATION.ID.eq(id))
            .and(WORKSPACE_INVITATION.WORKSPACE_ID.eq(workspace.value))
            .and(WORKSPACE_INVITATION.ACCEPTED_AT.isNull)
            .and(WORKSPACE_INVITATION.REVOKED_AT.isNull)
            .execute() > 0

    private fun toInvitation(record: Record) = PendingInvitation(
        id = record[WORKSPACE_INVITATION.ID]!!,
        workspace = WorkspaceId(record[WORKSPACE_INVITATION.WORKSPACE_ID]!!),
        email = record[WORKSPACE_INVITATION.EMAIL]!!,
        role = WorkspaceRole.of(record[WORKSPACE_INVITATION.ROLE]!!) ?: error("stored workspace role"),
        tokenHash = record[WORKSPACE_INVITATION.TOKEN_HASH]!!,
        invitedBy = UserId(record[WORKSPACE_INVITATION.INVITED_BY]!!),
        createdAt = record[WORKSPACE_INVITATION.CREATED_AT]!!.toInstant(),
        expiresAt = record[WORKSPACE_INVITATION.EXPIRES_AT]!!.toInstant(),
        acceptedAt = record[WORKSPACE_INVITATION.ACCEPTED_AT]?.toInstant(),
        revokedAt = record[WORKSPACE_INVITATION.REVOKED_AT]?.toInstant(),
    )

    private fun at(instant: Instant) = instant.atOffset(ZoneOffset.UTC)
}
