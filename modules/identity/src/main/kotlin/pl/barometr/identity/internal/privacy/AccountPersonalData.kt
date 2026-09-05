package pl.barometr.identity.internal.privacy

import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Table
import org.jooq.TableField
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.internal.jooq.tables.references.DATA_EXPORT
import pl.barometr.identity.internal.jooq.tables.references.RECOVERY_CODE
import pl.barometr.identity.internal.jooq.tables.references.REFRESH_TOKENS
import pl.barometr.identity.internal.jooq.tables.references.SESSION
import pl.barometr.identity.internal.jooq.tables.references.TOTP_SECRET
import pl.barometr.identity.internal.jooq.tables.references.TRUSTED_DEVICE
import pl.barometr.identity.internal.jooq.tables.references.USERS
import pl.barometr.identity.internal.jooq.tables.references.USER_ROLES
import pl.barometr.identity.internal.jooq.tables.references.WORKSPACE
import pl.barometr.identity.internal.jooq.tables.references.WORKSPACE_INVITATION
import pl.barometr.identity.internal.jooq.tables.references.WORKSPACE_MEMBER
import pl.barometr.shared.ErasureReport
import pl.barometr.shared.PersonalDataExtract
import pl.barometr.shared.PersonalDataStore
import pl.barometr.shared.PersonalDataTable
import java.util.UUID

/**
 * What identity holds about somebody: the account itself, and everything that proves who
 * they are.
 *
 * **Nothing that proves anything is exported.** The password hash, the encrypted TOTP
 * secret, the hashes of refresh tokens and recovery codes and trusted devices are all
 * held about this person and none of them is put in a file that leaves the building: an
 * export is a right to know what is held, not a way to hand somebody's credentials to
 * whoever asks for the download. What is exported is the fact that they exist — when the
 * second factor was set up, how many codes are left, which devices are trusted.
 *
 * **A workspace with nobody left in it goes too.** The account cascade takes the
 * membership; an organisation's account with no members is a row nobody can reach, pay
 * for or close, and leaving one behind is the state deleting an account most easily
 * creates.
 */
@Component
class AccountPersonalData(private val dsl: DSLContext) : PersonalDataStore {

    override val category: String = "identity"

    @Transactional(readOnly = true)
    override fun personalDataOf(user: UUID): PersonalDataExtract = PersonalDataExtract(
        category = category,
        tables = listOf(
            PersonalDataTable(
                name = "account",
                rows = dsl.selectFrom(USERS).where(USERS.ID.eq(user)).fetch().map {
                    mapOf(
                        "id" to it.id.toString(),
                        "email" to it.email,
                        "enabled" to it.enabled?.toString(),
                        "created_at" to it.createdAt?.toInstant()?.toString(),
                    )
                },
            ),
            PersonalDataTable(
                name = "roles",
                rows = dsl.select(USER_ROLES.ROLE)
                    .from(USER_ROLES)
                    .where(USER_ROLES.USER_ID.eq(user))
                    .fetch { mapOf("role" to it.value1()) },
            ),
            PersonalDataTable(
                name = "session",
                rows = dsl.selectFrom(SESSION).where(SESSION.USER_ID.eq(user)).fetch().map {
                    mapOf(
                        "user_agent" to it.userAgent,
                        "client_ip" to it.clientIp?.address()?.hostAddress,
                        "created_at" to it.createdAt?.toInstant()?.toString(),
                        "last_seen_at" to it.lastSeenAt?.toInstant()?.toString(),
                        "revoked_at" to it.revokedAt?.toInstant()?.toString(),
                    )
                },
            ),
            PersonalDataTable(
                name = "trusted_device",
                rows = dsl.selectFrom(TRUSTED_DEVICE).where(TRUSTED_DEVICE.USER_ID.eq(user)).fetch().map {
                    mapOf(
                        "user_agent" to it.userAgent,
                        "trusted_at" to it.createdAt?.toInstant()?.toString(),
                        "expires_at" to it.expiresAt?.toInstant()?.toString(),
                        "last_used_at" to it.lastUsedAt?.toInstant()?.toString(),
                    )
                },
            ),
            PersonalDataTable(
                name = "two_factor",
                rows = dsl.selectFrom(TOTP_SECRET).where(TOTP_SECRET.USER_ID.eq(user)).fetch().map {
                    // The secret itself stays where it is; what is exported is that there
                    // is one and when it was confirmed.
                    mapOf(
                        "enrolled_at" to it.createdAt?.toInstant()?.toString(),
                        "confirmed_at" to it.confirmedAt?.toInstant()?.toString(),
                        "recovery_codes_unused" to
                            dsl.fetchCount(RECOVERY_CODE, RECOVERY_CODE.USER_ID.eq(user).and(RECOVERY_CODE.USED_AT.isNull))
                                .toString(),
                    )
                },
            ),
            PersonalDataTable(
                name = "workspace_membership",
                rows = dsl.select(WORKSPACE.NAME, WORKSPACE_MEMBER.ROLE, WORKSPACE_MEMBER.JOINED_AT)
                    .from(WORKSPACE_MEMBER)
                    .join(WORKSPACE).on(WORKSPACE.ID.eq(WORKSPACE_MEMBER.WORKSPACE_ID))
                    .where(WORKSPACE_MEMBER.USER_ID.eq(user))
                    .fetch {
                        mapOf(
                            "workspace" to it.value1(),
                            "role" to it.value2(),
                            "joined_at" to it.value3()?.toInstant()?.toString(),
                        )
                    },
            ),
            PersonalDataTable(
                name = "data_export",
                rows = dsl.selectFrom(DATA_EXPORT).where(DATA_EXPORT.USER_ID.eq(user)).fetch().map {
                    mapOf(
                        "requested_at" to it.requestedAt?.toInstant()?.toString(),
                        "status" to it.status,
                        "expires_at" to it.expiresAt?.toInstant()?.toString(),
                    )
                },
            ),
        ),
    )

    @Transactional
    override fun erasePersonalData(user: UUID): ErasureReport {
        val address = dsl.select(USERS.EMAIL).from(USERS).where(USERS.ID.eq(user)).fetchOne()?.value1()
        val orphaned = workspacesLeftEmptyBy(user)

        val deleted = mutableMapOf(
            // Counted before the account row goes, because the cascade takes all of these
            // with it and counting afterwards would report zero for rows that existed.
            "session" to count(SESSION, SESSION.USER_ID, user),
            "refresh_token" to count(REFRESH_TOKENS, REFRESH_TOKENS.USER_ID, user),
            "trusted_device" to count(TRUSTED_DEVICE, TRUSTED_DEVICE.USER_ID, user),
            "totp_secret" to count(TOTP_SECRET, TOTP_SECRET.USER_ID, user),
            "recovery_code" to count(RECOVERY_CODE, RECOVERY_CODE.USER_ID, user),
            "workspace_member" to count(WORKSPACE_MEMBER, WORKSPACE_MEMBER.USER_ID, user),
            "data_export" to count(DATA_EXPORT, DATA_EXPORT.USER_ID, user),
            // Invitations they sent, and invitations sent to them: both name a person.
            "workspace_invitation" to dsl.deleteFrom(WORKSPACE_INVITATION)
                .where(WORKSPACE_INVITATION.INVITED_BY.eq(user))
                .or(address?.let { WORKSPACE_INVITATION.EMAIL.eq(it.lowercase()) } ?: WORKSPACE_INVITATION.ID.isNull)
                .execute(),
        )

        deleted["account"] = dsl.deleteFrom(USERS).where(USERS.ID.eq(user)).execute()
        deleted["workspace"] = orphaned
            .takeIf { it.isNotEmpty() }
            ?.let { dsl.deleteFrom(WORKSPACE).where(WORKSPACE.ID.`in`(it)).execute() }
            ?: 0

        return ErasureReport(category = category, deleted = deleted, kept = emptyMap())
    }

    /**
     * Workspaces this account is the only member of.
     *
     * Computed before the deletion, because afterwards there is nothing left to compute
     * it from — the membership row is gone with the account.
     */
    private fun workspacesLeftEmptyBy(user: UUID): List<UUID> =
        dsl.select(WORKSPACE_MEMBER.WORKSPACE_ID)
            .from(WORKSPACE_MEMBER)
            .where(WORKSPACE_MEMBER.USER_ID.eq(user))
            .fetch { it.value1()!! }
            .filter { workspace ->
                dsl.fetchCount(WORKSPACE_MEMBER, WORKSPACE_MEMBER.WORKSPACE_ID.eq(workspace)) == 1
            }

    private fun count(table: Table<out Record>, owner: TableField<out Record, UUID?>, user: UUID): Int =
        dsl.fetchCount(table, owner.eq(user))
}
