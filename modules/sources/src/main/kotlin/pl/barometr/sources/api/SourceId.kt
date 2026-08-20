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

/**
 * Stable, human-readable key of a connector — `sejm`, `rcl`, `isap`.
 *
 * Distinct from [SourceId]: the identifier is what the database joins on, this
 * is what configuration files and logs refer to.
 */
@JvmInline
value class ConnectorId(val value: String) {
    init {
        require(value.matches(PATTERN)) { "Connector id must be lower-kebab-case: '$value'" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("[a-z][a-z0-9-]*")
    }
}
