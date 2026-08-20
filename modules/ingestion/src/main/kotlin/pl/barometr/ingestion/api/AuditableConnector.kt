package pl.barometr.ingestion.api

/**
 * A connector that can state what its source claims to hold.
 *
 * Separate from [BackfillConnector] because not every source publishes counts —
 * and a connector that cannot should say so by not implementing this, rather than
 * by returning numbers it invented.
 */
interface AuditableConnector : Connector {
    fun declaredVolumes(partition: BackfillPartition): List<DeclaredVolume>
}
