package pl.barometr.ingestion.api

import pl.barometr.sources.api.ConnectorId

/**
 * A connector reads from one source and hands payloads to a sink. That is all
 * it does.
 *
 * What it supports is expressed by the interfaces it implements, not by a list it
 * declares: a connector that reads increments is an [IncrementalConnector], and a
 * runtime asking for a mode it does not implement is a compile-time-visible fact
 * rather than a set that can disagree with the type.
 *
 * Methods block rather than suspend. The application runs on virtual threads, so
 * blocking IO costs nothing worth the complexity of a second concurrency model —
 * and a connector implementation stays readable top to bottom, which matters when
 * the interesting part is a source's pagination quirks.
 */
interface Connector {
    /** Identifies the connector to the registry, to configuration and to logs. */
    val id: ConnectorId
}
