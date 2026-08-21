package pl.barometr.alerts.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import pl.barometr.alerts.internal.jooq.tables.references.ALERT_DECISION
import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.ProfileId
import pl.barometr.shared.Ids
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Why somebody was or was not told. SQL only.
 */
@Repository
class AlertDecisionRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    fun record(
        owner: UserId,
        profile: ProfileId,
        item: ResolvedItem,
        outcome: AlertOutcome,
    ) {
        dsl.insertInto(ALERT_DECISION)
            .set(ALERT_DECISION.ID, Ids.next())
            .set(ALERT_DECISION.OWNER_ID, owner.value)
            .set(ALERT_DECISION.PROFILE_ID, profile.value)
            .set(ALERT_DECISION.SUBJECT_KIND, item.kind)
            .set(ALERT_DECISION.SUBJECT_ID, item.id)
            .set(ALERT_DECISION.EVENT_KEY, AlertKeys.eventOf(item))
            .set(ALERT_DECISION.DECISION, outcome.decision.wireName)
            .set(ALERT_DECISION.REASON, outcome.reason)
            .set(ALERT_DECISION.DECIDED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .execute()
    }

    /** The last [limit] decisions taken about this person, newest first. */
    fun listFor(owner: UserId, limit: Int): List<RecordedDecision> =
        dsl.selectFrom(ALERT_DECISION)
            .where(ALERT_DECISION.OWNER_ID.eq(owner.value))
            .orderBy(ALERT_DECISION.DECIDED_AT.desc())
            .limit(limit)
            .fetch {
                RecordedDecision(
                    subjectKind = it.subjectKind!!,
                    subjectId = it.subjectId!!,
                    decision = it.decision!!,
                    reason = it.reason!!,
                    decidedAt = it.decidedAt!!.toInstant(),
                )
            }
}
