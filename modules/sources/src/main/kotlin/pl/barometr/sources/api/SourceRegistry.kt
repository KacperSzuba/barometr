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

    /**
     * One source by id whether or not it is enabled.
     *
     * The counterpart to [enabledById], and the difference is the caller's question.
     * A queued job asks whether we may still *read* this source, so a row switched
     * off between enqueue and claim must not answer. Something deriving from what
     * was already archived asks only which source the bytes came from — and that
     * stays true after the source is switched off, which is exactly when the archive
     * matters most.
     */
    fun byId(id: SourceId): SourceDefinition?
}
