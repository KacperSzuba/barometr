package pl.barometr.sources.api

import pl.barometr.shared.Ids
import java.util.UUID

@JvmInline
value class SourceId(val value: UUID) {

    override fun toString(): String = value.toString()

    companion object {
        fun next(): SourceId = SourceId(Ids.next())

        fun parse(raw: String): SourceId = SourceId(UUID.fromString(raw))
    }
}
