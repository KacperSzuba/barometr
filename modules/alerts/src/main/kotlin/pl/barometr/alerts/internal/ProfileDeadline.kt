package pl.barometr.alerts.internal

import pl.barometr.legislative.api.ConsultationDeadline

/**
 * A consultation this profile is interested in, and why.
 *
 * The reason travels with it because a calendar entry has to survive being read six
 * weeks later by somebody who does not remember subscribing: "you watch *prawo
 * budowlane*" is the difference between a deadline and an unexplained appointment.
 */
data class ProfileDeadline(
    val consultation: ConsultationDeadline,
    val matchedKind: String,
    val matchedValue: String,
    /** Working days left when the feed was built, which is what a reader acts on. */
    val workingDaysLeft: Int,
)
