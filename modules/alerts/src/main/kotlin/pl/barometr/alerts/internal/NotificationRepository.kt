package pl.barometr.alerts.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import pl.barometr.alerts.internal.jooq.tables.references.NOTIFICATION
import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.InterestKind
import pl.barometr.profiles.api.MatchedInterest
import pl.barometr.profiles.api.ProfileId
import pl.barometr.shared.Ids
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * What people have been told. SQL only.
 */
@Repository
class NotificationRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * Writes the notification, or reports that this person has already had it.
     *
     * The unique index on `(owner_id, event_key)` is the arbiter rather than a read
     * before the write: a run that dies halfway and is repeated, or two instances
     * whose locks overlapped, must not produce a second copy — and the only place that
     * can be decided without a race is inside the insert.
     */
    fun raiseIfNew(
        owner: UserId,
        profile: ProfileId,
        profileVersion: Int,
        item: ResolvedItem,
        matchedBy: MatchedInterest,
    ): Boolean =
        dsl.insertInto(NOTIFICATION)
            .set(NOTIFICATION.ID, Ids.next())
            .set(NOTIFICATION.OWNER_ID, owner.value)
            .set(NOTIFICATION.PROFILE_ID, profile.value)
            .set(NOTIFICATION.PROFILE_VERSION, profileVersion)
            .set(NOTIFICATION.SUBJECT_KIND, item.kind)
            .set(NOTIFICATION.SUBJECT_ID, item.id)
            .set(NOTIFICATION.TITLE, item.title)
            .set(NOTIFICATION.MATCHED_KIND, matchedBy.kind.wireName)
            .set(NOTIFICATION.MATCHED_VALUE, matchedBy.value)
            .set(NOTIFICATION.EVENT_KEY, AlertKeys.eventOf(item))
            .set(NOTIFICATION.CASE_KEY, AlertKeys.caseOf(item))
            .set(NOTIFICATION.CREATED_AT, at(clock.instant()))
            .onConflict(NOTIFICATION.OWNER_ID, NOTIFICATION.EVENT_KEY)
            .doNothing()
            .execute() == 1

    /** Whether this person has already heard about this matter since [since]. */
    fun toldAboutCaseSince(owner: UserId, caseKey: String, since: Instant): Boolean =
        dsl.fetchExists(
            dsl.selectFrom(NOTIFICATION)
                .where(NOTIFICATION.OWNER_ID.eq(owner.value))
                .and(NOTIFICATION.CASE_KEY.eq(caseKey))
                .and(NOTIFICATION.CREATED_AT.ge(at(since))),
        )

    fun listFor(owner: UserId, limit: Int): List<Notification> =
        dsl.selectFrom(NOTIFICATION)
            .where(NOTIFICATION.OWNER_ID.eq(owner.value))
            .orderBy(NOTIFICATION.CREATED_AT.desc())
            .limit(limit)
            .fetch {
                Notification(
                    id = it.id!!,
                    owner = UserId(it.ownerId!!),
                    profile = ProfileId(it.profileId!!),
                    profileVersion = it.profileVersion!!,
                    subjectKind = it.subjectKind!!,
                    subjectId = it.subjectId!!,
                    title = it.title!!,
                    matchedBy = MatchedInterest(
                        // A kind stored by this system and unknown to it would mean a
                        // release removed one; the log below is what would explain the
                        // notification, so it fails rather than guesses.
                        InterestKind.of(it.matchedKind!!) ?: error("stored kind '${it.matchedKind}'"),
                        it.matchedValue!!,
                    ),
                    createdAt = it.createdAt!!.toInstant(),
                    readAt = it.readAt?.toInstant(),
                )
            }

    /** False when it is not this person's notification, which reads the same as absent. */
    fun markRead(owner: UserId, id: UUID): Boolean =
        dsl.update(NOTIFICATION)
            .set(NOTIFICATION.READ_AT, at(clock.instant()))
            .where(NOTIFICATION.ID.eq(id))
            .and(NOTIFICATION.OWNER_ID.eq(owner.value))
            .and(NOTIFICATION.READ_AT.isNull)
            .execute() == 1

    private fun at(instant: Instant) = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
}
