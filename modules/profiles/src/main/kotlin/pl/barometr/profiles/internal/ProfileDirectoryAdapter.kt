package pl.barometr.profiles.internal

import org.springframework.stereotype.Component
import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.ProfileDirectory
import pl.barometr.profiles.api.ProfileId

/** The ownership half of the contract, read straight from the profile. */
@Component
class ProfileDirectoryAdapter(private val profiles: InterestProfileRepository) : ProfileDirectory {

    override fun ownerOf(profile: ProfileId): UserId? = profiles.findCurrent(profile)?.owner
}
