package pl.barometr.profiles.api

import pl.barometr.identity.api.UserId

/**
 * Whose profile this is.
 *
 * Published for one reason: anything that lets somebody act on a profile — a standing
 * instruction to alert on it, a report built from it — has to establish first that it
 * is theirs, and only this context knows. Without it, a context would have to take a
 * caller's word for which profile they own, which is the whole of the check.
 */
interface ProfileDirectory {

    /** Null when there is no such profile. */
    fun ownerOf(profile: ProfileId): UserId?
}
