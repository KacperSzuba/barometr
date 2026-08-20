package pl.barometr.ingestion.api

import java.time.LocalDate

interface BackfillConnector : Connector {
    /**
     * Splits a window into units that can be interrupted and resumed
     * independently — the difference between a backfill that survives a restart
     * and one that starts over.
     */
    fun partitions(from: LocalDate, to: LocalDate): List<BackfillPartition>

    fun readPartitionChunk(
        partition: BackfillPartition,
        cursor: Cursor?,
        sink: RawDocumentSink,
    ): FetchResult
}
