package pl.barometr.legislative.internal

import java.time.Instant

/** A date somebody else fixed, not one this system guessed. */
data class HardDeadline(val on: Instant, val kind: HardDeadlineKind)
