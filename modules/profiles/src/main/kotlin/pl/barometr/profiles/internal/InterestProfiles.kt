package pl.barometr.profiles.internal

import org.springframework.stereotype.Service
import pl.barometr.identity.api.UserId

/**
 * What a subscriber may do to their own profiles.
 *
 * Every method takes the owner, and every one of them answers "not yours" the same way
 * as "no such thing" — see [UnknownProfileException]. Ownership is checked here rather
 * than in SQL predicates spread across the repository, so there is one place to read
 * to know that a profile cannot be reached sideways.
 */
@Service
class InterestProfiles(
    private val profiles: InterestProfileRepository,
    private val normalizer: InterestNormalizer,
) {

    fun ownedBy(owner: UserId): List<InterestProfile> = profiles.listOwnedBy(owner)

    fun read(owner: UserId, id: ProfileId): InterestProfile = own(owner, id)

    /**
     * A past version, verbatim.
     *
     * This is what makes an alert explicable a week later: it cites the version it
     * matched against, and that version still says exactly what it said then.
     */
    fun readVersion(owner: UserId, id: ProfileId, version: Int): InterestProfile {
        own(owner, id)
        return profiles.findVersion(id, version) ?: throw UnknownProfileException("$id@$version")
    }

    fun history(owner: UserId, id: ProfileId): List<ProfileVersion> {
        own(owner, id)
        return profiles.versionsOf(id)
    }

    fun create(owner: UserId, name: String, interests: List<Interest>): InterestProfile =
        profiles.create(owner, name.trim(), accept(interests))
            ?: throw DuplicateProfileNameException(name)

    /**
     * Replaces what the profile cares about, as a new version.
     *
     * Replaces rather than merges: the request states the whole profile, so removing
     * an interest is expressed by sending the rest — which is the only way a client
     * that renders a list of checkboxes can say "not that one any more" without a
     * second endpoint whose semantics would have to be explained.
     */
    fun revise(owner: UserId, id: ProfileId, interests: List<Interest>): InterestProfile {
        own(owner, id)
        return profiles.revise(id, accept(interests))
            ?: throw UnknownProfileException(id.toString())
    }

    fun rename(owner: UserId, id: ProfileId, name: String): InterestProfile {
        val profile = own(owner, id)
        val trimmed = name.trim()
        return when (profiles.rename(id, trimmed)) {
            RenameOutcome.RENAMED -> profile.copy(name = trimmed)
            RenameOutcome.NAME_TAKEN -> throw DuplicateProfileNameException(trimmed)
            RenameOutcome.NO_SUCH_PROFILE -> throw UnknownProfileException(id.toString())
        }
    }

    fun delete(owner: UserId, id: ProfileId) {
        own(owner, id)
        profiles.delete(id)
    }

    /**
     * Normalises every value into the vocabulary of its kind and drops what is said
     * twice.
     *
     * De-duplication happens after normalising, not before: `62.01.Z` and `62.01.z`
     * are the same interest and only look different until they are read.
     */
    private fun accept(interests: List<Interest>): List<Interest> =
        interests.map(normalizer::normalize).distinct()

    private fun own(owner: UserId, id: ProfileId): InterestProfile =
        profiles.findCurrent(id)?.takeIf { it.owner == owner }
            ?: throw UnknownProfileException(id.toString())
}
