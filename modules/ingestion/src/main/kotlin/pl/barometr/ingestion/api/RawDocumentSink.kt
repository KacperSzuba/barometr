package pl.barometr.ingestion.api

/**
 * The only way a connector writes anything.
 *
 * Everything a connector could get wrong lives behind this interface: hashing,
 * storing the payload under its content address, the `ON CONFLICT DO NOTHING`
 * insert, and publishing the event that starts the processing pipeline. A
 * connector cannot deduplicate incorrectly because it has no access to the
 * mechanism, and cannot write to the wrong source because the sink handed to it
 * is already bound to one.
 *
 * Which is what makes a connector a pure function from a source to a stream of
 * payloads — and testable against a recorded response with no database in sight.
 */
interface RawDocumentSink {

    fun archive(payload: RawPayload): SinkOutcome

    /**
     * Recorded when a response carries a field the connector does not know, or
     * omits one it expects. A source changing shape underneath us shows up here
     * before it shows up as missing data.
     */
    fun recordSchemaWarning(warning: SchemaWarning)
}
