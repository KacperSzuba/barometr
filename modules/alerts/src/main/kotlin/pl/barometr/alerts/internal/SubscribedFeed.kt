package pl.barometr.alerts.internal

import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.ProfileId

/**
 * What a feed token names: whose profile the calendar is about.
 *
 * The owner travels with the profile because the feed has two questions to answer and
 * they are asked of different contexts — what this profile is interested in, and what
 * this person has already written in about.
 */
data class SubscribedFeed(val profile: ProfileId, val owner: UserId)
