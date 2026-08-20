package pl.barometr.shared

import com.github.f4b6a3.uuid.UuidCreator
import java.util.UUID

/**
 * Identifier generation for the whole system.
 *
 * UUIDv7 is time-ordered, so inserts land at the right edge of the B-tree
 * instead of scattering across it the way v4 does — the locality of a bigint
 * without a shared sequence to coordinate on.
 *
 * Generated in the application, never by the database: `gen_random_uuid()` is
 * not portable, and an aggregate that knows its own identity before it is
 * persisted is far easier to work with.
 */
object Ids {
    fun next(): UUID = UuidCreator.getTimeOrderedEpoch()
}
