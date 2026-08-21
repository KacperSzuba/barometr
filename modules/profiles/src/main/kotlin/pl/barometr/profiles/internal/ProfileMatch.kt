package pl.barometr.profiles.internal

/** One thing a profile catches, and the interest that caught it. */
data class ProfileMatch(
    val interest: Interest,
    val kind: String,
    val id: String,
    val title: String,
    val eli: String?,
)
