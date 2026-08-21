package pl.barometr.alerts.internal

import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.InterestKind
import pl.barometr.profiles.api.InterestedProfile
import pl.barometr.profiles.api.LegislativeItem
import pl.barometr.profiles.api.MatchedInterest
import pl.barometr.profiles.api.ProfileId
import pl.barometr.profiles.api.ProfileMatching

/**
 * Whom a document interests, stated by the test rather than worked out.
 *
 * What profiles mean by a match is settled where it lives, against real Postgres and a
 * real analyser. What is under test here is everything that happens *after* the answer
 * comes back, so the answer is an input.
 */
class FakeMatching : ProfileMatching {
    private val interests = mutableMapOf<String, MutableList<InterestedProfile>>()

    fun catches(
        subjectId: String,
        profile: ProfileId,
        owner: UserId,
        kind: InterestKind = InterestKind.KEYWORD,
        value: String = "prawo budowlane",
        version: Int = 1,
    ) {
        interests.getOrPut(subjectId) { mutableListOf() }
            .add(InterestedProfile(profile, owner, version, MatchedInterest(kind, value)))
    }

    override fun profilesInterestedIn(item: LegislativeItem): List<InterestedProfile> =
        interests[item.id].orEmpty()
}
