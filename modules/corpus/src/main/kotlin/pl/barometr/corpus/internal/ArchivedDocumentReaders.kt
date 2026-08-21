package pl.barometr.corpus.internal

import org.springframework.stereotype.Component
import pl.barometr.sources.api.ConnectorId

/**
 * The readers this deployment carries, indexed by the connector whose archive they
 * can read. Mirrors ingestion's `ConnectorRegistry`, for the same reason: one place
 * builds the index, so there is one answer to which reader serves a source.
 */
@Component
class ArchivedDocumentReaders(readers: List<ArchivedDocumentReader>) {

    private val byConnector: Map<ConnectorId, ArchivedDocumentReader> =
        readers.associateBy { it.connectorId }

    /**
     * Null when a source has been added without a reader. Not an error to swallow:
     * the caller says so in the log and in a counter, because the alternative —
     * inventing a description — would put a source's documents in the corpus under a
     * shape nobody chose.
     */
    fun forConnector(connectorId: ConnectorId): ArchivedDocumentReader? = byConnector[connectorId]
}
