package pl.barometr.ingestion.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.barometr.ingestion.api.BackfillConnector
import pl.barometr.ingestion.api.BackfillPartition
import pl.barometr.ingestion.api.Connector
import pl.barometr.ingestion.api.Cursor
import pl.barometr.ingestion.api.FetchResult
import pl.barometr.ingestion.api.IncrementalConnector
import pl.barometr.ingestion.api.SchemaWarning
import pl.barometr.sources.api.IngestionCursors
import pl.barometr.sources.api.IngestionMode
import pl.barometr.sources.api.RunOutcome
import pl.barometr.sources.api.RunReport
import pl.barometr.sources.api.SourceDefinition
import pl.barometr.sources.api.SourceRuns

/**
 * Runs one connector once.
 *
 * Orchestration and nothing else: it opens a run, hands the connector its position
 * and a sink, commits the new position and closes the run. Judging whether the
 * result looked healthy belongs to [SourceHealthMonitor]; writing documents belongs
 * to [RawDocumentArchiver]; reading the source belongs to the connector, which knows
 * nothing about runs, cursors or health.
 */
@Component
class ConnectorRunner(
    private val connectors: ConnectorRegistry,
    private val sinkFactory: RawDocumentSinkFactory,
    private val cursors: IngestionCursors,
    private val runs: SourceRuns,
    private val health: SourceHealthMonitor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        log.info("Connectors registered: {}", connectors.registeredIds.map { it.value })
    }

    /**
     * [partition] names the resumable unit for a backfill — a parliamentary term, a
     * year. Empty for incremental, which has a single position.
     */
    fun readSourceOnce(source: SourceDefinition, mode: IngestionMode, partition: String = "") {
        val connector = connectors.byId(source.connectorId)
            ?: throw UnknownConnectorException(source.connectorId)
        val runId = runs.start(source.id, mode)
        val sink = sinkFactory.forRun(source.id, runId.value)

        try {
            val result = readFrom(connector, source, mode, partition, sink)

            // Committed only after a successful read: a position advanced past
            // documents that were never stored would skip them permanently, and for
            // a backfill it is what makes an interrupted partition resume rather
            // than restart.
            result.nextCursor?.let { cursors.save(source.id, mode, it.position, partition) }

            val report = reportOf(sink)
            runs.finish(runId, RunOutcome.SUCCEEDED, report)
            health.reviewCompletedRun(source, mode, report, result.sourceUnchanged)
        } catch (failure: Exception) {
            runs.finish(runId, RunOutcome.FAILED, failureReportOf(sink, failure))
            // Rethrown so the queue applies its backoff. Swallowing it here would
            // make a broken source look like a series of quiet successes.
            throw failure
        }
    }

    private fun readFrom(
        connector: Connector,
        source: SourceDefinition,
        mode: IngestionMode,
        partition: String,
        sink: RunBoundRawDocumentSink,
    ): FetchResult {
        val position = cursors.load(source.id, mode, partition)?.let { Cursor(mode, it) }

        // Matched rather than cast: what a connector supports is the set of
        // interfaces it implements, so a mode it cannot serve is a typed refusal
        // instead of a ClassCastException raised after the run row was opened.
        return when (mode) {
            IngestionMode.INCREMENTAL -> {
                if (connector !is IncrementalConnector) {
                    throw ModeNotSupportedException(source.connectorId, mode)
                }
                connector.readChangesSince(position, sink)
            }

            IngestionMode.BACKFILL -> {
                if (connector !is BackfillConnector) {
                    throw ModeNotSupportedException(source.connectorId, mode)
                }
                require(partition.isNotEmpty()) { "Backfill needs a partition to read" }
                connector.readPartitionChunk(
                    BackfillPartition(key = partition, label = partition),
                    position,
                    sink,
                )
            }
        }
    }

    /**
     * The sink counted, so nothing else has to. It is the only participant that sees
     * every payload *and* knows whether the archive already held it — and reading a
     * connector's own tally on success while reading the sink's on failure was two
     * answers to one question.
     */
    private fun reportOf(sink: RunBoundRawDocumentSink) = RunReport(
        documentsSeen = sink.documentsSeen,
        documentsStored = sink.documentsStored,
        errors = 0,
        schemaWarnings = sink.schemaWarnings.map(::describe),
    )

    private fun failureReportOf(sink: RunBoundRawDocumentSink, failure: Exception) = RunReport(
        // Whatever got through before the failure still counts as archived.
        documentsSeen = sink.documentsSeen,
        documentsStored = sink.documentsStored,
        errors = 1,
        schemaWarnings = sink.schemaWarnings.map(::describe),
        failureReason = failure.message ?: failure::class.qualifiedName,
    )

    private fun describe(warning: SchemaWarning): String =
        "${warning.kind}:${warning.path}" + (warning.detail?.let { " ($it)" } ?: "")
}
