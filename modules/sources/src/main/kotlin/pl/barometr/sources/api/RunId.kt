package pl.barometr.sources.api

import pl.barometr.shared.Ids
import java.util.UUID

@JvmInline
value class RunId(val value: UUID) {
    companion object {
        fun next() = RunId(Ids.next())
    }
}
