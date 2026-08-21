package pl.barometr.profiles.api

/**
 * Something that happened in the legislature, in the little of it profiles need to
 * decide whether anybody asked to hear about it.
 *
 * Not the act and not the draft — those belong to legislative, and a profile has no
 * business holding one. What is here is what an interest can be compared against: what
 * it is called, what it is, and the address people quote it by.
 */
data class LegislativeItem(
    /** `act` or `draft`, in the vocabulary the rest of the system already uses. */
    val kind: String,
    val id: String,
    val title: String,
    val eli: String? = null,
)
