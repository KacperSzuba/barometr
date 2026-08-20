package pl.barometr.ingestion.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.barometr.ingestion.api.ArchiveCompleteness
import pl.barometr.ingestion.api.BackfillPartition
import pl.barometr.ingestion.api.CompletenessReport
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.IngestionCursors
import pl.barometr.sources.api.IngestionMode
import pl.barometr.sources.api.SourceRegistry
import java.util.Locale

/**
 * Compares what a source says it holds against what the archive actually contains.
 *
 * This is what turns a finished backfill from a claim into a fact. A replay that
 * dropped a page fails silently by nature — every run reports success, the counts
 * simply come out short — so the only way to know is to ask the source for its own
 * tally and subtract.
 *
 * Audits the partitions that have actually been replayed, read from their cursors:
 * reporting a gap in a term nobody asked for would be noise, not a finding.
 */
@Service
class ArchiveCompletenessAuditor(
    private val connectors: ConnectorRegistry,
    private val sources: SourceRegistry,
    private val cursors: IngestionCursors,
    private val documents: RawDocumentRepository,
    private val properties: IngestionProperties,
) : ArchiveCompleteness {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun audit(connectorId: ConnectorId): CompletenessReport {
        val source = sources.byConnector(connectorId) ?: throw UnknownConnectorException(connectorId)
        val connector = connectors.auditable(connectorId)
            ?: throw ConnectorNotAuditableException(connectorId)

        val findings = cursors.partitions(source.id, IngestionMode.BACKFILL)
            .keys
            .sorted()
            .flatMap { partitionKey ->
                connector.declaredVolumes(BackfillPartition(partitionKey, partitionKey))
                    .map { declared ->
                        CompletenessReport.Finding(
                            partition = declared.partition,
                            kind = declared.kind,
                            declared = declared.declaredCount,
                            archived = documents.countDirectlyUnder(
                                source.id,
                                declared.externalIdPrefix,
                            ),
                            isAuthoritative = declared.isAuthoritative,
                        )
                    }
            }

        val report = CompletenessReport(connectorId, findings, properties.completenessTolerance)
        report.gaps.forEach { gap ->
            log.warn(
                "Completeness gap in {} {}/{}: declared {}, archived {} ({}% missing)",
                connectorId, gap.partition, gap.kind, gap.declared, gap.archived,
                String.format(Locale.ROOT, "%.2f", gap.missingFraction * 100),
            )
        }
        return report
    }
}
