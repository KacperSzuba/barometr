package pl.barometr.alerts.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.alerts.internal.jooq.tables.references.ALERT_RULE
import pl.barometr.alerts.internal.jooq.tables.references.ALERT_RULE_STAGE
import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.ProfileId
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Standing instructions. SQL only.
 */
@Repository
class AlertRuleRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    fun forProfile(profile: ProfileId): AlertRule? =
        read(ALERT_RULE.PROFILE_ID.eq(profile.value)).firstOrNull()

    fun ownedBy(owner: UserId): List<AlertRule> = read(ALERT_RULE.OWNER_ID.eq(owner.value))

    fun byId(id: AlertRuleId): AlertRule? = read(ALERT_RULE.ID.eq(id.value)).firstOrNull()

    /** Null when this profile already has a rule — one profile, one standing instruction. */
    @Transactional
    fun create(owner: UserId, profile: ProfileId, stages: Set<String>): AlertRule? {
        val id = AlertRuleId.next()
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

        val created = dsl.insertInto(ALERT_RULE)
            .set(ALERT_RULE.ID, id.value)
            .set(ALERT_RULE.OWNER_ID, owner.value)
            .set(ALERT_RULE.PROFILE_ID, profile.value)
            .set(ALERT_RULE.ENABLED, true)
            .set(ALERT_RULE.CREATED_AT, now)
            .set(ALERT_RULE.UPDATED_AT, now)
            .onConflict(ALERT_RULE.PROFILE_ID)
            .doNothing()
            .execute()
        if (created == 0) return null

        writeStages(id, stages)
        return AlertRule(id, owner, profile, enabled = true, stages = stages)
    }

    @Transactional
    fun update(rule: AlertRule): AlertRule {
        dsl.update(ALERT_RULE)
            .set(ALERT_RULE.ENABLED, rule.enabled)
            .set(ALERT_RULE.UPDATED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .where(ALERT_RULE.ID.eq(rule.id.value))
            .execute()

        dsl.deleteFrom(ALERT_RULE_STAGE).where(ALERT_RULE_STAGE.RULE_ID.eq(rule.id.value)).execute()
        writeStages(rule.id, rule.stages)
        return rule
    }

    fun delete(id: AlertRuleId): Boolean =
        dsl.deleteFrom(ALERT_RULE).where(ALERT_RULE.ID.eq(id.value)).execute() == 1

    private fun writeStages(id: AlertRuleId, stages: Set<String>) {
        if (stages.isEmpty()) return

        dsl.batch(
            stages.map { stage ->
                dsl.insertInto(ALERT_RULE_STAGE)
                    .set(ALERT_RULE_STAGE.RULE_ID, id.value)
                    .set(ALERT_RULE_STAGE.STAGE, stage)
                    .onConflictDoNothing()
            },
        ).execute()
    }

    /**
     * One query for the rules and their stages. A rule with no stages watches every
     * stage, which is why this is an outer join and an empty set rather than an absent
     * row meaning something else.
     */
    private fun read(condition: org.jooq.Condition): List<AlertRule> =
        dsl.select(
            ALERT_RULE.ID,
            ALERT_RULE.OWNER_ID,
            ALERT_RULE.PROFILE_ID,
            ALERT_RULE.ENABLED,
            ALERT_RULE_STAGE.STAGE,
        )
            .from(ALERT_RULE)
            .leftJoin(ALERT_RULE_STAGE).on(ALERT_RULE_STAGE.RULE_ID.eq(ALERT_RULE.ID))
            .where(condition)
            .fetchGroups(ALERT_RULE.ID)
            .map { (id, rows) ->
                val head = rows.first()
                AlertRule(
                    id = AlertRuleId(id!!),
                    owner = UserId(head[ALERT_RULE.OWNER_ID]!!),
                    profile = ProfileId(head[ALERT_RULE.PROFILE_ID]!!),
                    enabled = head[ALERT_RULE.ENABLED]!!,
                    stages = rows.mapNotNull { it[ALERT_RULE_STAGE.STAGE] }.toSet(),
                )
            }
}
