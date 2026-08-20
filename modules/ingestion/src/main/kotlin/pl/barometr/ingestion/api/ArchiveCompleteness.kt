package pl.barometr.ingestion.api

import pl.barometr.sources.api.ConnectorId

/**
 * A count the source itself publishes, to check our archive against.
 *
 * The distinction in [isAuthoritative] is the whole point of this type. A figure the
 * API states independently — "this term contains 3205 prints" — genuinely proves
 * completeness. A figure derived from the same list we ingested only proves we
 * finished reading that list, which catches a truncated backfill but cannot detect
 * that the list itself was short. Reporting the two as if they were the same
 * evidence would make the completeness report worse than useless: falsely
 * reassuring.
 */
data class DeclaredVolume(
    val partition: String,
    /** What kind of thing is being counted: `print`, `proceeding`. */
    val kind: String,
    /** Archive rows whose external id starts with this belong to the count. */
    val externalIdPrefix: String,
    val declaredCount: Int,
    val isAuthoritative: Boolean,
)

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

data class CompletenessReport(
    val connectorId: ConnectorId,
    val findings: List<Finding>,
    /** Fraction of a declared count that may be missing before it counts as a gap. */
    val tolerance: Double,
) {
    data class Finding(
        val partition: String,
        val kind: String,
        val declared: Int,
        val archived: Int,
        val isAuthoritative: Boolean,
    ) {
        /** Negative when we hold more than declared — revisions, or a stale count. */
        val missingFraction: Double
            get() = if (declared == 0) 0.0 else (declared - archived).toDouble() / declared
    }

    val gaps: List<Finding> get() = findings.filter { it.missingFraction > tolerance }

    /** Only authoritative findings can establish completeness. */
    val isComplete: Boolean
        get() = gaps.isEmpty() && findings.any { it.isAuthoritative }
}

interface ArchiveCompleteness {
    fun audit(connectorId: ConnectorId): CompletenessReport
}
