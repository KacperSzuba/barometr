package pl.barometr.alerts.internal

import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.MatchedInterest
import pl.barometr.profiles.api.ProfileId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One thing one person was told, and everything needed to say why.
 *
 * The profile *version* is part of it rather than a pointer to the profile as it
 * stands: somebody who edits their profile twice this week must still be able to see
 * what caught Monday's act.
 */
data class Notification(
    val id: UUID,
    val owner: UserId,
    val profile: ProfileId,
    val profileVersion: Int,
    val subjectKind: String,
    val subjectId: String,
    val title: String,
    val urgency: Urgency,
    /** How much it mattered when it was decided, and what made it so. */
    val significance: Significance,
    val matchedBy: MatchedInterest,
    /**
     * The day comments are due, on the one kind of notification that is about a date
     * rather than about news. Null on every other, and frozen here for the reason
     * significance is: what somebody was told is a record of a moment.
     */
    val closesOn: LocalDate?,
    val createdAt: Instant,
    val readAt: Instant?,
)
