package pl.barometr.identity.internal.workspace

import org.jooq.Record
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.types.YearToSecond
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import pl.barometr.identity.internal.jooq.tables.references.WORKSPACE
import pl.barometr.identity.internal.jooq.tables.references.WORKSPACE_MEMBER
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** [Workspaces] over jOOQ. */
@Repository
@Transactional(readOnly = true)
class JooqWorkspaces(private val dsl: DSLContext) : Workspaces {

    /**
     * A workspace and its first member in one transaction.
     *
     * They are one fact: a workspace with nobody in it is a row nobody can reach, and
     * whoever created it is its owner by construction rather than by a later call that
     * might not happen.
     */
    @Transactional
    override fun create(workspace: Workspace, owner: UserId, at: Instant): Workspace {
        dsl.insertInto(WORKSPACE)
            .set(WORKSPACE.ID, workspace.id.value)
            .set(WORKSPACE.NAME, workspace.name)
            .set(WORKSPACE.SEATS, workspace.seats)
            .set(WORKSPACE.REQUIRE_TWO_FACTOR, workspace.requireTwoFactor)
            .set(WORKSPACE.SESSION_IDLE_TIMEOUT, workspace.sessionIdleTimeout?.let(YearToSecond::valueOf))
            .set(WORKSPACE.CREATED_AT, at(workspace.createdAt))
            .execute()

        addMember(WorkspaceMembership(workspace.id, owner, WorkspaceRole.OWNER, at))

        return workspace
    }

    override fun byId(id: WorkspaceId): Workspace? =
        dsl.selectFrom(WORKSPACE).where(WORKSPACE.ID.eq(id.value)).fetchOne(::toWorkspace)

    @Transactional
    override fun updatePolicy(id: WorkspaceId, requireTwoFactor: Boolean, idleTimeout: Duration?): Boolean =
        dsl.update(WORKSPACE)
            .set(WORKSPACE.REQUIRE_TWO_FACTOR, requireTwoFactor)
            .set(WORKSPACE.SESSION_IDLE_TIMEOUT, idleTimeout?.let(YearToSecond::valueOf))
            .where(WORKSPACE.ID.eq(id.value))
            .execute() > 0

    @Transactional
    override fun updateSeats(id: WorkspaceId, seats: Int): Boolean =
        dsl.update(WORKSPACE).set(WORKSPACE.SEATS, seats).where(WORKSPACE.ID.eq(id.value)).execute() > 0

    override fun membershipsOf(user: UserId): List<WorkspaceMembership> =
        dsl.selectFrom(WORKSPACE_MEMBER)
            .where(WORKSPACE_MEMBER.USER_ID.eq(user.value))
            .orderBy(WORKSPACE_MEMBER.JOINED_AT)
            .fetch(::toMembership)

    override fun membersOf(id: WorkspaceId): List<WorkspaceMembership> =
        dsl.selectFrom(WORKSPACE_MEMBER)
            .where(WORKSPACE_MEMBER.WORKSPACE_ID.eq(id.value))
            .orderBy(WORKSPACE_MEMBER.JOINED_AT)
            .fetch(::toMembership)

    override fun membership(id: WorkspaceId, user: UserId): WorkspaceMembership? =
        dsl.selectFrom(WORKSPACE_MEMBER)
            .where(WORKSPACE_MEMBER.WORKSPACE_ID.eq(id.value))
            .and(WORKSPACE_MEMBER.USER_ID.eq(user.value))
            .fetchOne(::toMembership)

    /** @return false when they were already in it, which is not an error anywhere above. */
    @Transactional
    override fun addMember(membership: WorkspaceMembership): Boolean =
        dsl.insertInto(WORKSPACE_MEMBER)
            .set(WORKSPACE_MEMBER.WORKSPACE_ID, membership.workspace.value)
            .set(WORKSPACE_MEMBER.USER_ID, membership.user.value)
            .set(WORKSPACE_MEMBER.ROLE, membership.role.wireName)
            .set(WORKSPACE_MEMBER.JOINED_AT, at(membership.joinedAt))
            .onConflictDoNothing()
            .execute() > 0

    @Transactional
    override fun changeRole(id: WorkspaceId, user: UserId, role: WorkspaceRole): Boolean =
        dsl.update(WORKSPACE_MEMBER)
            .set(WORKSPACE_MEMBER.ROLE, role.wireName)
            .where(WORKSPACE_MEMBER.WORKSPACE_ID.eq(id.value))
            .and(WORKSPACE_MEMBER.USER_ID.eq(user.value))
            .execute() > 0

    @Transactional
    override fun removeMember(id: WorkspaceId, user: UserId): Boolean =
        dsl.deleteFrom(WORKSPACE_MEMBER)
            .where(WORKSPACE_MEMBER.WORKSPACE_ID.eq(id.value))
            .and(WORKSPACE_MEMBER.USER_ID.eq(user.value))
            .execute() > 0

    override fun countMembers(id: WorkspaceId): Int =
        dsl.fetchCount(WORKSPACE_MEMBER, WORKSPACE_MEMBER.WORKSPACE_ID.eq(id.value))

    override fun countOwners(id: WorkspaceId): Int =
        dsl.fetchCount(
            WORKSPACE_MEMBER,
            WORKSPACE_MEMBER.WORKSPACE_ID.eq(id.value).and(WORKSPACE_MEMBER.ROLE.eq(WorkspaceRole.OWNER.wireName)),
        )

    override fun anyRequiresTwoFactor(user: UserId): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(WORKSPACE_MEMBER)
                .join(WORKSPACE).on(WORKSPACE.ID.eq(WORKSPACE_MEMBER.WORKSPACE_ID))
                .where(WORKSPACE_MEMBER.USER_ID.eq(user.value))
                .and(WORKSPACE.REQUIRE_TWO_FACTOR.isTrue),
        )

    override fun strictestIdleTimeout(user: UserId): Duration? =
        dsl.select(DSL.min(WORKSPACE.SESSION_IDLE_TIMEOUT))
            .from(WORKSPACE_MEMBER)
            .join(WORKSPACE).on(WORKSPACE.ID.eq(WORKSPACE_MEMBER.WORKSPACE_ID))
            .where(WORKSPACE_MEMBER.USER_ID.eq(user.value))
            .and(WORKSPACE.SESSION_IDLE_TIMEOUT.isNotNull)
            .fetchOne()
            ?.value1()
            ?.toDuration()

    private fun toWorkspace(record: Record) = Workspace(
        id = WorkspaceId(record[WORKSPACE.ID]!!),
        name = record[WORKSPACE.NAME]!!,
        seats = record[WORKSPACE.SEATS]!!,
        requireTwoFactor = record[WORKSPACE.REQUIRE_TWO_FACTOR]!!,
        sessionIdleTimeout = record[WORKSPACE.SESSION_IDLE_TIMEOUT]?.toDuration(),
        createdAt = record[WORKSPACE.CREATED_AT]!!.toInstant(),
    )

    private fun toMembership(record: Record) = WorkspaceMembership(
        workspace = WorkspaceId(record[WORKSPACE_MEMBER.WORKSPACE_ID]!!),
        user = UserId(record[WORKSPACE_MEMBER.USER_ID]!!),
        // A stored role this enum does not know would mean the `CHECK` and the code
        // drifted apart, which is a state no caller can interpret.
        role = WorkspaceRole.of(record[WORKSPACE_MEMBER.ROLE]!!) ?: error("stored workspace role"),
        joinedAt = record[WORKSPACE_MEMBER.JOINED_AT]!!.toInstant(),
    )

    private fun at(instant: Instant) = instant.atOffset(ZoneOffset.UTC)
}
