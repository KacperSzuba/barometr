package pl.barometr.legislative.api

import java.util.UUID

/**
 * A act as this system holds it.
 *
 * Distinct from its ELI, which is the identifier the world uses: an act exists here
 * from the moment a source mentions it, and the two are the same thing addressed by
 * different authorities.
 */
@JvmInline
value class ActId(val value: UUID) {
    override fun toString(): String = value.toString()
}
