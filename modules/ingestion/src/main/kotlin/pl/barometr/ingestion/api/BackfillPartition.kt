package pl.barometr.ingestion.api

/** A resumable unit of archive: one parliamentary term, one year, one month. */
data class BackfillPartition(
    val key: String,
    val label: String,
)
