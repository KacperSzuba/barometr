package pl.barometr.profiles.api

import pl.barometr.identity.api.UserId

/**
 * One profile that asked to hear about an item, at the version that asked.
 *
 * The version travels with the answer so that whatever records the notification
 * records what the profile said at the time — an alert explained by today's profile,
 * after two edits, explains nothing.
 */
data class InterestedProfile(
    val profile: ProfileId,
    val owner: UserId,
    val version: Int,
    val matchedBy: MatchedInterest,
)
