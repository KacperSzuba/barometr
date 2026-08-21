package pl.barometr.profiles.api

/**
 * Who asked to hear about this.
 *
 * The one thing this context publishes, and the reason it holds matching at all: the
 * screen where somebody edits a profile and the run that decides who gets an e-mail
 * must give the same answer, and they only do that if they ask the same code.
 */
interface ProfileMatching {

    /**
     * Every live profile that catches [item], one entry per interest that caught it.
     *
     * Live, meaning each profile's current version. A profile edited yesterday is
     * matched as it reads today; the older versions are kept to explain what was
     * already sent, not to keep sending it.
     */
    fun profilesInterestedIn(item: LegislativeItem): List<InterestedProfile>
}
