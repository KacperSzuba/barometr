package pl.barometr.legislative.api

import java.util.UUID

/**
 * A draft — one bill or resolution on its way through the process.
 *
 * Distinct from the [ActId] it may become: a draft exists from the day a ministry
 * files it, and most never become an act at all.
 */
@JvmInline
value class DraftId(val value: UUID) {
    override fun toString(): String = value.toString()
}
