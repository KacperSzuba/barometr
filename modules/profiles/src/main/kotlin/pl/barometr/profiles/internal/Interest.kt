package pl.barometr.profiles.internal

/**
 * One thing a subscriber said about what they want.
 *
 * [excluded] is not the absence of an interest, it is an interest in *not* being told:
 * "everything in construction except this one act" is a sentence a profile has to be
 * able to say, and saying it by leaving something out is impossible.
 */
data class Interest(
    val kind: InterestKind,
    val value: String,
    val excluded: Boolean = false,
)
