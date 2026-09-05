package pl.barometr.alerts.internal

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import pl.barometr.legislative.api.ConsultationCalendar
import pl.barometr.legislative.api.LegislativeKind
import pl.barometr.profiles.api.LegislativeItem
import pl.barometr.profiles.api.ProfileId
import pl.barometr.profiles.api.ProfileMatching
import pl.barometr.shared.WorkingDays
import java.time.Clock
import java.time.LocalDate

/**
 * What one profile still has to answer: the consultations it is interested in that are
 * open, minus the ones this person has already written in about.
 *
 * **Matched the same way an alert is.** The question "does this profile care about this
 * draft" is asked of profiles, through the same port the alert run uses, so a feed and
 * a notification can never disagree about what somebody subscribed to. A second
 * matching rule written here would be a second answer to "why am I seeing this".
 *
 * **One question per consultation.** The port answers the push direction — given a
 * thing, who wants it — and this is the pull direction, so it asks once per open
 * consultation. That is tens of indexed queries on a quarter's worth of consultations,
 * run when a calendar client refreshes, and it keeps the matching rules in exactly one
 * place. If the window ever holds thousands, the port grows a pull method; today it
 * would be a second implementation of the same rules for no gain.
 *
 * **What has been filed is gone from the list.** A deadline somebody has already met is
 * not a deadline, and a calendar that keeps showing it teaches its reader to ignore it.
 */
@Service
@Transactional(readOnly = true)
class ProfileDeadlines(
    private val calendar: ConsultationCalendar,
    private val matching: ProfileMatching,
    private val filings: ConsultationFilingRepository,
    private val properties: CalendarProperties,
    private val clock: Clock,
) {

    fun openFor(profile: ProfileId, owner: UserId): List<ProfileDeadline> {
        val today = LocalDate.now(clock)
        val open = calendar.closingBetween(today, today.plusDays(properties.horizonDays))
        val answered = filings.filedAmong(owner, open.map { it.id })

        return open
            .filterNot { it.id in answered }
            .mapNotNull { deadline ->
                val caught = matching
                    .profilesInterestedIn(
                        LegislativeItem(
                            kind = LegislativeKind.DRAFT,
                            id = deadline.draftId.value.toString(),
                            title = deadline.draftTitle,
                        ),
                    )
                    .firstOrNull { it.profile == profile }
                    ?: return@mapNotNull null

                ProfileDeadline(
                    consultation = deadline,
                    matchedKind = caught.matchedBy.kind.wireName,
                    matchedValue = caught.matchedBy.value,
                    workingDaysLeft = WorkingDays.between(today, deadline.closesOn),
                )
            }
    }
}
