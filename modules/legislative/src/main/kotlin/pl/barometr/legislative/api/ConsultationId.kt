package pl.barometr.legislative.api

import java.util.UUID

/**
 * One draft's public consultation — the period in which anybody may file comments on
 * it.
 *
 * Its own identity rather than the draft's, because a draft can be sent out for
 * comment more than once: a rewritten bill goes back to consultation with a new
 * deadline, and a calendar entry that moved would silently replace the term somebody
 * had already planned around.
 */
@JvmInline
value class ConsultationId(val value: UUID) {
    override fun toString(): String = value.toString()
}
