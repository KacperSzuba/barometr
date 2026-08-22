package pl.barometr.alerts.internal

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.profiles.api.InterestKind
import pl.barometr.profiles.api.InterestedProfile
import pl.barometr.profiles.api.LegislativeItem
import pl.barometr.profiles.api.ProfileMatching
import java.time.Clock
import java.time.Duration

/**
 * Decides, for one thing that moved, who hears about it.
 *
 * The product's promise is one notification about a matter rather than eight, so most
 * of this is about not sending: the same news twice, a second piece of news about a
 * matter somebody heard about this morning, a stage they said they did not care about.
 * Every one of those is written down as a decision — support's first question is "why
 * did I not get an alert", and an engine that cannot answer it is an engine nobody can
 * defend.
 */
@Service
class AlertRaiser(
    private val matching: ProfileMatching,
    private val rules: AlertRuleRepository,
    private val notifications: NotificationRepository,
    private val decisions: AlertDecisionRepository,
    private val clock: Clock,
) {

    @Transactional
    fun raiseFor(item: ResolvedItem): Int {
        val interested = matching.profilesInterestedIn(
            LegislativeItem(item.kind, item.id, item.title, item.eli),
        )

        return interested.groupBy { it.profile }
            .count { (_, caught) -> judge(caught.minWith(BY_SPECIFICITY), item) }
    }

    private fun judge(interested: InterestedProfile, item: ResolvedItem): Boolean {
        val rule = rules.forProfile(interested.profile)
        val outcome = decide(rule, interested, item)

        decisions.record(interested.owner, interested.profile, item, outcome)
        return outcome.decision == AlertOutcome.Decision.RAISED
    }

    /**
     * The order is the policy. A rule that does not want this at all is asked first,
     * because it is the cheapest and the most explicit; the window comes before the
     * write, since being told this morning is a better reason to stay quiet than the
     * news being technically new.
     */
    private fun decide(
        rule: AlertRule?,
        interested: InterestedProfile,
        item: ResolvedItem,
    ): AlertOutcome = when {
        rule == null -> AlertOutcome.NO_RULE
        !rule.enabled -> AlertOutcome.RULE_DISABLED
        !rule.watches(item.stage) -> AlertOutcome.STAGE_NOT_WATCHED
        toldRecentlyAbout(interested, item) -> AlertOutcome.CASE_RECENTLY_RAISED
        !raise(rule, interested, item) -> AlertOutcome.ALREADY_TOLD
        else -> AlertOutcome.RAISED
    }

    private fun toldRecentlyAbout(interested: InterestedProfile, item: ResolvedItem): Boolean =
        notifications.toldAboutCaseSince(
            interested.owner,
            AlertKeys.caseOf(item),
            clock.instant().minus(CASE_WINDOW),
        )

    private fun raise(rule: AlertRule, interested: InterestedProfile, item: ResolvedItem): Boolean =
        notifications.raiseIfNew(
            interested.owner,
            interested.profile,
            interested.version,
            item,
            interested.matchedBy,
            rule.urgency,
        )

    private companion object {
        /**
         * A day, because that is the rhythm of the thing being reported: a draft can
         * move twice between breakfast and lunch, and hearing about both is hearing
         * about neither.
         */
        val CASE_WINDOW: Duration = Duration.ofHours(24)

        /**
         * When two interests catch the same thing, the notification cites the most
         * specific one. "You watch this act" is a better answer to "why am I being
         * told this" than "you watch the word *ustawa*", and it is also the one the
         * person is least likely to want turned off.
         */
        val SPECIFICITY = listOf(
            InterestKind.ACT,
            InterestKind.DRAFT,
            InterestKind.PKD,
            InterestKind.REGION,
            InterestKind.KEYWORD,
        )

        val BY_SPECIFICITY = compareBy<InterestedProfile> { SPECIFICITY.indexOf(it.matchedBy.kind) }
    }
}
