package pl.barometr.profiles.internal

/**
 * What a profile catches today, said in full — including the parts of it that catch
 * nothing, and why.
 *
 * Three lists rather than one, because "found nothing" and "cannot yet find anything"
 * are different answers and a person editing a profile has to be able to tell them
 * apart. Returning an empty result for both would make a correct industry code look
 * like a typo.
 */
data class ProfilePreview(
    val version: Int,
    val matches: List<ProfileMatch>,
    /** Chosen, matchable, and matching nothing in the archive right now. */
    val silent: List<Interest>,
    /**
     * Kinds nothing can match yet. An industry or a place is matched by comparing it to
     * what an act is *about*, and no act carries that tag until the impact analysis
     * that assigns them exists — so these are recorded, kept, and honestly reported as
     * dormant rather than quietly returning nothing.
     */
    val dormant: List<Interest>,
)
