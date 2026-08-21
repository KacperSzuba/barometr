package pl.barometr.profiles.internal

import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.ProfileId

/**
 * A profile as it stands at one version.
 *
 * The version is part of the value rather than metadata about it. An alert cites the
 * version it was matched against, so that somebody told on Tuesday that an act
 * concerned them can still see why on Friday, after editing the profile twice — and
 * that only works if a version is a complete, immutable statement rather than a
 * revision number beside a mutable row.
 */
data class InterestProfile(
    val id: ProfileId,
    val owner: UserId,
    val name: String,
    val version: Int,
    val interests: List<Interest>,
) {
    fun of(kind: InterestKind): List<Interest> = interests.filter { it.kind == kind }
}
