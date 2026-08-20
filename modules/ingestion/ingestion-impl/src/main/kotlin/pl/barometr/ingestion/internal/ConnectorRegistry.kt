package pl.barometr.ingestion.internal

import org.springframework.stereotype.Component
import pl.barometr.ingestion.api.AuditableConnector
import pl.barometr.ingestion.api.Connector
import pl.barometr.sources.api.ConnectorId

/**
 * The connectors this deployment carries, indexed by id.
 *
 * One place builds the index. Three classes used to inject `List<Connector>` and
 * build the same map themselves, which meant three copies of one lookup and three
 * chances for them to answer differently.
 */
@Component
class ConnectorRegistry(connectors: List<Connector>) {

    private val byId: Map<ConnectorId, Connector> = connectors.associateBy { it.id }

    val registeredIds: Set<ConnectorId> get() = byId.keys

    fun byId(connectorId: ConnectorId): Connector? = byId[connectorId]

    /**
     * Null when the connector states no counts of its own. Not an oversight on its
     * part: a source that publishes no tally is better represented by silence than
     * by a number we invented.
     */
    fun auditable(connectorId: ConnectorId): AuditableConnector? =
        byId[connectorId] as? AuditableConnector
}
