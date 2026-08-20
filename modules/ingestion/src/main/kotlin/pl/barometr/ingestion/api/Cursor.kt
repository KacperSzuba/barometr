package pl.barometr.ingestion.api

import pl.barometr.sources.api.IngestionMode

/**
 * Where a connector resumes from.
 *
 * Untyped on purpose: one source paginates by date, another by print number, a
 * third by opaque continuation token. Typing this would mean a schema migration
 * every time one connector learns a new trick.
 */
data class Cursor(val mode: IngestionMode, val position: Map<String, String>) {
    operator fun get(key: String): String? = position[key]

    companion object {
        fun start(mode: IngestionMode) = Cursor(mode, emptyMap())

        /**
         * Written into a partition's position by a backfill connector once it has
         * read that partition to the end, and read by the dispatcher to decide
         * whether to bring the partition back.
         *
         * The one key in an otherwise opaque map that is a contract between the two,
         * so it is declared where the contract is. It used to be spelled out
         * separately in the dispatcher and in each connector — three declarations of
         * one string, any of which could have drifted.
         */
        const val PARTITION_DONE = "done"
    }
}
