package pl.barometr.profiles.internal

import java.util.UUID

/** One subscriber's statement of what they care about. */
@JvmInline
value class ProfileId(val value: UUID) {
    override fun toString(): String = value.toString()
}
