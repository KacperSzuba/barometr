package pl.barometr.profiles.internal

import java.time.Instant

/** One entry in a profile's history: which version, and when it was written. */
data class ProfileVersion(val version: Int, val createdAt: Instant)
