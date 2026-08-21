package pl.barometr.alerts.internal

import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.ProfileId

/**
 * When a profile's matches are worth waking somebody for.
 *
 * Separate from the profile on purpose. A profile says what somebody cares about;
 * this says what they want doing about it — and those change for different reasons.
 * Narrowing "tell me at once" to "tell me once it reaches the Senate" must not be an
 * edit to what construction means to them.
 */
data class AlertRule(
    val id: AlertRuleId,
    val owner: UserId,
    val profile: ProfileId,
    val enabled: Boolean,
    /** Stages worth hearing about. Empty means every stage. */
    val stages: Set<String>,
) {

    /**
     * A published act passes any stage filter.
     *
     * A stage is a thing a draft is at; an act has arrived. Somebody who asked to hear
     * only from the Senate onwards was asking to be spared the early noise, not to
     * miss the law being published — and reading their filter as "no acts either"
     * would silently do exactly that.
     */
    fun watches(stage: String?): Boolean = stages.isEmpty() || stage == null || stage in stages
}
