package pl.barometr.ingestion.api

interface IncrementalConnector : Connector {
    /**
     * Reads what changed since [cursor]. Must be safe to call with a cursor it
     * has already processed: re-reading a range is a no-op at the sink, so
     * correctness never depends on the cursor being exact.
     */
    fun readChangesSince(cursor: Cursor?, sink: RawDocumentSink): FetchResult
}
