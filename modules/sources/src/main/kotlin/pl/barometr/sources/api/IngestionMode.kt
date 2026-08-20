package pl.barometr.sources.api

enum class IngestionMode(val wireName: String) {
    /** Every few minutes, resuming from the last cursor. */
    INCREMENTAL("incremental"),

    /**
     * Years of archive, deliberately slow. A separate mode rather than a flag,
     * because it needs its own rate limit, its own cursor and a priority low
     * enough that a five-year replay never delays today's documents.
     */
    BACKFILL("backfill"),
    ;

    companion object {
        fun of(wireName: String): IngestionMode =
            entries.firstOrNull { it.wireName == wireName }
                ?: error("Unknown ingestion mode '$wireName'")
    }
}
