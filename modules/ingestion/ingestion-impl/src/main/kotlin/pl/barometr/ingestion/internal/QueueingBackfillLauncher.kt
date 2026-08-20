package pl.barometr.ingestion.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.barometr.ingestion.api.BackfillConnector
import pl.barometr.ingestion.api.BackfillLauncher
import pl.barometr.ingestion.api.BackfillPlan
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.IngestionMode
import pl.barometr.sources.api.SourceRegistry
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Turns a replay window into one queued job per partition.
 *
 * Queued rather than executed: a partition is a long-running unit, so it belongs
 * in the queue where it gets retries, backoff and a dead letter for free — and
 * where killing the process loses at most the partition in flight, which resumes
 * from its own cursor.
 */
@Component
class QueueingBackfillLauncher(
    private val connectors: ConnectorRegistry,
    private val sources: SourceRegistry,
    private val runQueue: IngestionRunQueue,
    private val clock: Clock,
) : BackfillLauncher {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun launch(connectorId: ConnectorId, from: LocalDate, to: LocalDate): BackfillPlan {
        // Every failure below is something a caller can ask for, so each is a domain
        // failure with a status of its own rather than an IllegalStateException that
        // would reach the client as a server fault.
        if (from.isAfter(to)) throw InvalidBackfillWindowException()

        val source = sources.byConnector(connectorId) ?: throw UnknownConnectorException(connectorId)
        val connector = connectors.byId(connectorId) as? BackfillConnector
            ?: throw BackfillNotSupportedException(connectorId)

        val partitions = connector.partitions(from, to)
        val now = clock.instant()

        // Priority keeps a five-year replay behind live ingestion, and the
        // per-partition dedup key makes relaunching the same window a no-op rather
        // than a duplicate crawl.
        val queued = partitions.count { partition ->
            runQueue.queueRun(source, IngestionMode.BACKFILL, now, partition.key)
        }

        log.info(
            "Backfill for {} over {}..{}: {} partition(s), {} queued, {} already in flight",
            connectorId, from, to, partitions.size, queued, partitions.size - queued,
        )

        return BackfillPlan(
            connectorId = connectorId,
            partitions = partitions,
            queued = queued,
            skipped = partitions.size - queued,
        )
    }
}
