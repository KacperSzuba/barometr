package pl.barometr.ingestion.api

import pl.barometr.sources.api.ConnectorId

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
