package pl.barometr.sources.api

/** Read port over the registry. Nothing outside `sources` touches its tables. */
interface SourceRegistry {
    fun enabled(): List<SourceDefinition>

    fun byConnector(connectorId: ConnectorId): SourceDefinition?

    /**
     * One enabled source by id, or null.
     *
     * Deliberately filtered to enabled sources rather than returning any row: the
     * caller is a queued job asking whether this source is still one we are allowed
     * to read, and a source switched off between enqueue and claim must not run.
     */
    fun enabledById(id: SourceId): SourceDefinition?
}
