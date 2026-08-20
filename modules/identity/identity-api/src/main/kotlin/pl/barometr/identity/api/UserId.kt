package pl.barometr.identity.api

import pl.barometr.shared.Ids
import java.util.UUID

/**
 * Typed identifier, so a `UserId` can never be passed where a `SourceId` or a
 * `DocumentId` is expected. In a system with this many entity kinds that
 * mix-up is otherwise a matter of time, and the compiler catches it for free.
 */
@JvmInline
value class UserId(val value: UUID) {

    override fun toString(): String = value.toString()

    companion object {
        fun next(): UserId = UserId(Ids.next())

        fun parse(raw: String): UserId = UserId(UUID.fromString(raw))
    }
}
