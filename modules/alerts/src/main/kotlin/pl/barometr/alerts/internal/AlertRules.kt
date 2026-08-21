package pl.barometr.alerts.internal

import org.springframework.stereotype.Service
import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.ProfileDirectory
import pl.barometr.profiles.api.ProfileId

/**
 * What a person may do to their own standing instructions.
 *
 * The profile is checked against the caller before anything else, and that check is
 * the reason this class exists: a rule names a profile by identifier, and without
 * asking whose it is, anybody holding somebody else's identifier could switch their
 * alerts on and off.
 */
@Service
class AlertRules(
    private val rules: AlertRuleRepository,
    private val profiles: ProfileDirectory,
) {

    fun ownedBy(owner: UserId): List<AlertRule> = rules.ownedBy(owner)

    fun create(
        owner: UserId,
        profile: ProfileId,
        stages: Set<String>,
        urgency: Urgency,
    ): AlertRule {
        if (profiles.ownerOf(profile) != owner) throw UnknownProfileException(profile.toString())

        return rules.create(owner, profile, stages, urgency)
            ?: throw DuplicateAlertRuleException(profile.toString())
    }

    fun update(
        owner: UserId,
        id: AlertRuleId,
        enabled: Boolean,
        stages: Set<String>,
        urgency: Urgency,
    ): AlertRule =
        rules.update(own(owner, id).copy(enabled = enabled, stages = stages, urgency = urgency))

    fun delete(owner: UserId, id: AlertRuleId) {
        own(owner, id)
        rules.delete(id)
    }

    private fun own(owner: UserId, id: AlertRuleId): AlertRule =
        rules.byId(id)?.takeIf { it.owner == owner } ?: throw UnknownAlertRuleException(id.toString())
}
